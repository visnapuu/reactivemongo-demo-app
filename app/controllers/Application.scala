package controllers

import models._
import org.joda.time._
import play.api._
import play.api.libs.iteratee._
import play.api.mvc._
import play.api.Play.current
import play.modules.reactivemongo._
import scala.concurrent.{ExecutionContext, Future}
import reactivemongo.api._
import reactivemongo.api.gridfs._
import reactivemongo.bson._
import reactivemongo.bson.handlers.DefaultBSONHandlers.DefaultBSONDocumentWriter
import reactivemongo.bson.handlers.DefaultBSONHandlers.DefaultBSONReaderHandler
import java.io.ByteArrayOutputStream

object Articles extends Controller with MongoController {
  val db = ReactiveMongoPlugin.db
  val collection = db("articles")
  // a GridFS store named 'attachments'
  val gridFS = new GridFS(db, "attachments")

  // let's build an index on our gridfs chunks collection if none
  gridFS.ensureIndex().onComplete {
    case index =>
      Logger.info(s"Checked index, result is $index")
  }

  // list all articles and sort them
  def index = Action { implicit request =>
    Async {
      implicit val reader = Article.ArticleBSONReader
      val sort = getSort(request)
      // build a selection document with an empty query and a sort subdocument ('$orderby')
      val query = BSONDocument(
        "$orderby" -> sort,
        "$query" -> BSONDocument()
      )
      val activeSort = request.queryString.get("sort").flatMap(_.headOption).getOrElse("none")
      // the future cursor of documents
      val found = collection.find(query)
      // build (asynchronously) a list containing all the articles
      found.toList.map { articles =>
        Ok(views.html.articles(articles, activeSort))
      }
    }
  }

  def showCreationForm = Action {
    Ok(views.html.editArticle(None, Article.form, None))
  }

  def showEditForm(id: String) = Action {
    implicit val reader = Article.ArticleBSONReader

    Async {
      val objectId = new BSONObjectID(id)
      // get the documents having this id (there will be 0 or 1 result)
      val cursor = collection.find(BSONDocument("_id" -> objectId))
      // ... so we get optionally the matching article, if any
      // let's use for-comprehensions to compose futures (see http://doc.akka.io/docs/akka/2.0.3/scala/futures.html#For_Comprehensions for more information)
      for {
        // get a future option of article
        maybeArticle <- cursor.headOption
        // if there is some article, return a future of result with the article and its attachments
        result <- maybeArticle.map { article =>
          import reactivemongo.api.gridfs.Implicits.DefaultReadFileReader
          // search for the matching attachments
          // find(...).toList returns a future list of documents (here, a future list of ReadFileEntry)
          gridFS.find(BSONDocument("article" -> article.id.get)).toList.map { files =>
            val filesWithId = files.map { file =>
              file.id.asInstanceOf[BSONObjectID].stringify -> file
            }
            Ok(views.html.editArticle(Some(id), Article.form.fill(article), Some(filesWithId)))
          }
        }.getOrElse(Future(NotFound))
      } yield result
    }
  }

  def create = Action { implicit request =>
    Article.form.bindFromRequest.fold(
      errors => Ok(views.html.editArticle(None, errors, None)),
      // if no error, then insert the article into the 'articles' collection
      article => AsyncResult {
        collection.insert(article.copy(creationDate = Some(new DateTime()), updateDate = Some(new DateTime()))).map( _ =>
          Redirect(routes.Articles.index)
        )
      }
    )
  }

  def edit(id: String) = Action { implicit request =>
    Article.form.bindFromRequest.fold(
      errors => Ok(views.html.editArticle(Some(id), errors, None)),
      article => AsyncResult {
        val objectId = new BSONObjectID(id)
        // create a modifier document, ie a document that contains the update operations to run onto the documents matching the query
        val modifier = BSONDocument(
          // this modifier will set the fields 'updateDate', 'title', 'content', and 'publisher'
          "$set" -> BSONDocument(
            "updateDate" -> BSONDateTime(new DateTime().getMillis),
            "title" -> BSONString(article.title),
            "content" -> BSONString(article.content),
            "publisher" -> BSONString(article.publisher)))
        // ok, let's do the update
        collection.update(BSONDocument("_id" -> objectId), modifier).map { _ =>
          Redirect(routes.Articles.index)
        }
      }
    )
  }

  def delete(id: String) = Action {
    Async {
      import reactivemongo.api.gridfs.Implicits.DefaultReadFileReader
      // let's collect all the attachments matching that match the article to delete
      gridFS.find(BSONDocument("article" -> new BSONObjectID(id))).toList.flatMap { files =>
        // for each attachment, delete their chunks and then their file entry
        val deletions = files.map { file =>
          gridFS.remove(file)
        }
        Future.sequence(deletions)
      }.flatMap { _ =>
        // now, the last operation: remove the article
        collection.remove(BSONDocument("_id" -> new BSONObjectID(id)))
      }.map(_ => Ok).recover { case _ => InternalServerError}
    }
  }

  // save the uploaded file as an attachment of the article with the given id
  def saveAttachment(id: String) = Action(gridFSBodyParser(gridFS)) { request =>
    // the reader that allows the 'find' method to return a future Cursor[Article]
    implicit val reader = Article.ArticleBSONReader
    // first, get the attachment matching the given id, and get the first result (if any)
    val cursor = collection.find(BSONDocument("_id" -> new BSONObjectID(id)))
    val uploaded = cursor.headOption

    val futureUpload = for {
      // we filter the future to get it successful only if there is a matching Article
      article <- uploaded.filter(_.isDefined).map(_.get)
      // we wait (non-blocking) for the upload to complete.
      putResult <- request.body.files.head.ref
      // when the upload is complete, we add the article id to the file entry (in order to find the attachments of the article)
      result <- gridFS.files.update(BSONDocument("_id" -> putResult.id), BSONDocument("$set" -> BSONDocument("article" -> article.id.get)))
    } yield result

    Async {
      futureUpload.map {
        case _ => Redirect(routes.Articles.showEditForm(id))
      }.recover {
        case _ => BadRequest
      }
    }
  }

  def getAttachment(id: String) = Action {
    Async {
      import reactivemongo.api.gridfs.Implicits.DefaultReadFileReader
      // find the matching attachment, if any, and streams it to the client
      val file = gridFS.find(BSONDocument("_id" -> new BSONObjectID(id)))
      serve(gridFS, file)
    }
  }

  def removeAttachment(id: String) = Action {
    Async {
      gridFS.remove(new BSONObjectID(id)).map(_ => Ok).recover { case _ => InternalServerError }
    }
  }

  private def getSort(request: Request[_]) = {
    request.queryString.get("sort").map { fields =>
      val sortBy = for {
        order <- fields.map { field =>
          if(field.startsWith("-"))
            field.drop(1) -> -1
          else field -> 1
        }
        if order._1 == "title" || order._1 == "publisher" || order._1 == "creationDate" || order._1 == "updateDate"
      } yield order._1 -> BSONInteger(order._2)
      BSONDocument(sortBy :_*)
    }
  }
}


object GeneralREs extends Controller with MongoController {
  val db = ReactiveMongoPlugin.db
  val collection = db("generalre")
  // a GridFS store named 'attachments'
  val gridFS = new GridFS(db, "attachments")



  // let's build an index on our gridfs chunks collection if none
  gridFS.ensureIndex().onComplete {
    case index =>
      Logger.info(s"Checked index, result is $index")
  }

  // list all articles and sort them
  def index = Action { implicit request =>
    Async {
      implicit val reader = GeneralRE.GeneralREBSONReader
      val sort = getSort(request)
      // build a selection document with an empty query and a sort subdocument ('$orderby')
      val query = BSONDocument(
        "$orderby" -> sort,
        "$query" -> BSONDocument()
      )

      val activeSort = request.queryString.get("sort").flatMap(_.headOption).getOrElse("none")
      // the future cursor of documents
      val found = collection.find(query)
      // build (asynchronously) a list containing all the articles
      found.toList.map { generalres =>
        Ok(views.html.generalres(generalres, activeSort))
      }
    }
  }

  def showCreationForm = Action {
    Ok(views.html.editGeneralRE(None, GeneralRE.form, None))
  }

  def showEditForm(id: String) = Action {
    implicit val reader = GeneralRE.GeneralREBSONReader

    Async {
      val objectId = new BSONObjectID(id)
      // get the documents having this id (there will be 0 or 1 result)
      val cursor = collection.find(BSONDocument("_id" -> objectId))
      // ... so we get optionally the matching article, if any
      // let's use for-comprehensions to compose futures (see http://doc.akka.io/docs/akka/2.0.3/scala/futures.html#For_Comprehensions for more information)
      for {
      // get a future option of article
        maybeGeneralRE <- cursor.headOption
        // if there is some article, return a future of result with the article and its attachments
        result <- maybeGeneralRE.map { generalre =>
          import reactivemongo.api.gridfs.Implicits.DefaultReadFileReader
          // search for the matching attachments
          // find(...).toList returns a future list of documents (here, a future list of ReadFileEntry)
          gridFS.find(BSONDocument("generalre" -> generalre.id.get)).toList.map { files =>
            val filesWithId = files.map { file =>
              file.id.asInstanceOf[BSONObjectID].stringify -> file
            }
            Ok(views.html.editGeneralRE(Some(id), GeneralRE.form.fill(generalre), Some(filesWithId)))
          }
        }.getOrElse(Future(NotFound))
      } yield result
    }
  }

  def create = Action { implicit request =>
    GeneralRE.form.bindFromRequest.fold(
      errors => Ok(views.html.editGeneralRE(None, errors, None)),
      // if no error, then insert the article into the 'articles' collection
      generalre => AsyncResult {
        collection.insert(generalre.copy()).map( _ =>
          Redirect(routes.GeneralREs.index)
        )
      }
    )
  }

  def edit(id: String) = Action { implicit request =>
    GeneralRE.form.bindFromRequest.fold(
      errors => Ok(views.html.editGeneralRE(Some(id), errors, None)),
      generalre => AsyncResult {
        val objectId = new BSONObjectID(id)
        // create a modifier document, ie a document that contains the update operations to run onto the documents matching the query
        val modifier = BSONDocument(
          // this modifier will set the fields 'updateDate', 'title', 'content', and 'publisher'
          "$set" -> BSONDocument(
            "updateDate" -> BSONDateTime(new DateTime().getMillis),
            "account_id" -> BSONString(generalre.account_id),
            "account_name" -> BSONString(generalre.account_name),
            "transaction_type" -> BSONString(generalre.transaction_type),
            "trade_date" -> BSONString(generalre.trade_date),
            "security_name" -> BSONString(generalre.security_name)
        )
        )
        // ok, let's do the update
        collection.update(BSONDocument("_id" -> objectId), modifier).map { _ =>
          Redirect(routes.GeneralREs.index)
        }
      }
    )
  }

  def delete(id: String) = Action {
    Async {
      import reactivemongo.api.gridfs.Implicits.DefaultReadFileReader
      // let's collect all the attachments matching that match the article to delete
      gridFS.find(BSONDocument("generalre" -> new BSONObjectID(id))).toList.flatMap { files =>
      // for each attachment, delete their chunks and then their file entry
        val deletions = files.map { file =>
          gridFS.remove(file)
        }
        Future.sequence(deletions)
      }.flatMap { _ =>
      // now, the last operation: remove the article
        collection.remove(BSONDocument("_id" -> new BSONObjectID(id)))
      }.map(_ => Ok).recover { case _ => InternalServerError}
    }
  }

  // save the uploaded file as an attachment of the article with the given id
  def saveAttachment(id: String) = Action(gridFSBodyParser(gridFS)) { request =>
  // the reader that allows the 'find' method to return a future Cursor[Article]
    implicit val reader = GeneralRE.GeneralREBSONReader
    // first, get the attachment matching the given id, and get the first result (if any)
    val cursor = collection.find(BSONDocument("_id" -> new BSONObjectID(id)))
    val uploaded = cursor.headOption

    val futureUpload = for {
    // we filter the future to get it successful only if there is a matching Article
      generalre <- uploaded.filter(_.isDefined).map(_.get)
      // we wait (non-blocking) for the upload to complete.
      putResult <- request.body.files.head.ref
      // when the upload is complete, we add the article id to the file entry (in order to find the attachments of the article)
      result <- gridFS.files.update(BSONDocument("_id" -> putResult.id), BSONDocument("$set" -> BSONDocument("generalre" -> generalre.id.get)))
    } yield result

    Async {
      futureUpload.map {
        case _ => Redirect(routes.GeneralREs.showEditForm(id))
      }.recover {
        case _ => BadRequest
      }
    }
  }

  def getAttachment(id: String) = Action {
    Async {
      import reactivemongo.api.gridfs.Implicits.DefaultReadFileReader
      // find the matching attachment, if any, and streams it to the client
      val file = gridFS.find(BSONDocument("_id" -> new BSONObjectID(id)))
      serve(gridFS, file)
    }
  }

  def removeAttachment(id: String) = Action {
    Async {
      gridFS.remove(new BSONObjectID(id)).map(_ => Ok).recover { case _ => InternalServerError }
    }
  }

  private def getSort(request: Request[_]) = {
    request.queryString.get("sort").map { fields =>
      val sortBy = for {
        order <- fields.map { field =>
          if(field.startsWith("-"))
            field.drop(1) -> -1
          else field -> 1
        }
        if order._1 == "account_id" || order._1 == "account_name" || order._1 == "transaction_type" || order._1 == "security_name"
      } yield order._1 -> BSONInteger(order._2)
      BSONDocument(sortBy :_*)
    }
  }
}


object Tradings extends Controller with MongoController {
  val db = ReactiveMongoPlugin.db
  val collection = db("trading")
  // a GridFS store named 'attachments'
  val gridFS = new GridFS(db, "attachments")



  // let's build an index on our gridfs chunks collection if none
  gridFS.ensureIndex().onComplete {
    case index =>
      Logger.info(s"Checked index, result is $index")
  }

  // list all articles and sort them
  def index = Action { implicit request =>
    Async {
      implicit val reader = Trading.TradingBSONReader
      val sort = getSort(request)
      val q = if (request.queryString.contains("report")) BSONDocument("report_name" -> getReport(request)) else (BSONDocument("report_name" -> BSONString("goobers")))
      val query = BSONDocument(
        "$orderby" -> sort,
        "$query" -> q
      )

      val activeSort = request.queryString.get("sort").flatMap(_.headOption).getOrElse("none")
      val activeReport = request.queryString.get("report").flatMap(_.headOption).getOrElse("none")
      // the future cursor of documents
      val found = collection.find(query)
      // build (asynchronously) a list containing all the articles
      found.toList.map { tradings =>
        Ok(views.html.tradings(tradings, activeSort, activeReport))
      }
    }
  }

  def showCreationForm = Action {
    Ok(views.html.editTrading(None, Trading.form, None))
  }

  def showEditForm(id: String) = Action {
    implicit val reader = Trading.TradingBSONReader

    Async {
      val objectId = new BSONObjectID(id)
      // get the documents having this id (there will be 0 or 1 result)
      val cursor = collection.find(BSONDocument("_id" -> objectId))
      // ... so we get optionally the matching article, if any
      // let's use for-comprehensions to compose futures (see http://doc.akka.io/docs/akka/2.0.3/scala/futures.html#For_Comprehensions for more information)
      for {
      // get a future option of article
        maybeTrading <- cursor.headOption
        // if there is some article, return a future of result with the article and its attachments
        result <- maybeTrading.map { trading =>
          import reactivemongo.api.gridfs.Implicits.DefaultReadFileReader
          // search for the matching attachments
          // find(...).toList returns a future list of documents (here, a future list of ReadFileEntry)
          gridFS.find(BSONDocument("trading" -> trading.id.get)).toList.map { files =>
            val filesWithId = files.map { file =>
              file.id.asInstanceOf[BSONObjectID].stringify -> file
            }
            Ok(views.html.editTrading(Some(id), Trading.form.fill(trading), Some(filesWithId)))
          }
        }.getOrElse(Future(NotFound))
      } yield result
    }
  }

  def create = Action { implicit request =>
    Trading.form.bindFromRequest.fold(
      errors => Ok(views.html.editTrading(None, errors, None)),
      // if no error, then insert the article into the 'articles' collection
      trading => AsyncResult {
        collection.insert(trading.copy()).map( _ =>
          Redirect(routes.Tradings.index)
        )
      }
    )
  }

  def edit(id: String) = Action { implicit request =>
    Trading.form.bindFromRequest.fold(
      errors => Ok(views.html.editTrading(Some(id), errors, None)),
      trading => AsyncResult {
        val objectId = new BSONObjectID(id)
        // create a modifier document, ie a document that contains the update operations to run onto the documents matching the query
        val modifier = BSONDocument(
          // this modifier will set the fields 'updateDate', 'title', 'content', and 'publisher'
          "$set" -> BSONDocument(
            "updateDate" -> BSONDateTime(new DateTime().getMillis),
            "account_id" -> BSONString(trading.account_id),
            "account_description" -> BSONString(trading.account_description),
            "transaction_type" -> BSONString(trading.transaction_type),
            "trade_date" -> BSONDateTime(trading.trade_date.get.getMillis),
            "security_name" -> BSONString(trading.security_name)
          )
        )
        // ok, let's do the update
        collection.update(BSONDocument("_id" -> objectId), modifier).map { _ =>
          Redirect(routes.Tradings.index)
        }
      }
    )
  }

  def delete(id: String) = Action {
    Async {
      import reactivemongo.api.gridfs.Implicits.DefaultReadFileReader
      // let's collect all the attachments matching that match the article to delete
      gridFS.find(BSONDocument("trading" -> new BSONObjectID(id))).toList.flatMap { files =>
      // for each attachment, delete their chunks and then their file entry
        val deletions = files.map { file =>
          gridFS.remove(file)
        }
        Future.sequence(deletions)
      }.flatMap { _ =>
      // now, the last operation: remove the article
        collection.remove(BSONDocument("_id" -> new BSONObjectID(id)))
      }.map(_ => Ok).recover { case _ => InternalServerError}
    }
  }

  // save the uploaded file as an attachment of the article with the given id
  def saveAttachment(id: String) = Action(gridFSBodyParser(gridFS)) { request =>
  // the reader that allows the 'find' method to return a future Cursor[Article]
    implicit val reader = Trading.TradingBSONReader
    // first, get the attachment matching the given id, and get the first result (if any)
    val cursor = collection.find(BSONDocument("_id" -> new BSONObjectID(id)))
    val uploaded = cursor.headOption

    val futureUpload = for {
    // we filter the future to get it successful only if there is a matching Article
      trading <- uploaded.filter(_.isDefined).map(_.get)
      // we wait (non-blocking) for the upload to complete.
      putResult <- request.body.files.head.ref
      // when the upload is complete, we add the article id to the file entry (in order to find the attachments of the article)
      result <- gridFS.files.update(BSONDocument("_id" -> putResult.id), BSONDocument("$set" -> BSONDocument("trading" -> trading.id.get)))
    } yield result

    Async {
      futureUpload.map {
        case _ => Redirect(routes.Tradings.showEditForm(id))
      }.recover {
        case _ => BadRequest
      }
    }
  }

  def getAttachment(id: String) = Action {
    Async {
      import reactivemongo.api.gridfs.Implicits.DefaultReadFileReader
      // find the matching attachment, if any, and streams it to the client
      val file = gridFS.find(BSONDocument("_id" -> new BSONObjectID(id)))
      serve(gridFS, file)
    }
  }

  def removeAttachment(id: String) = Action {
    Async {
      gridFS.remove(new BSONObjectID(id)).map(_ => Ok).recover { case _ => InternalServerError }
    }
  }

  private def getSort(request: Request[_]) = {
    request.queryString.get("sort").map { fields =>
      val sortBy = for {
        order <- fields.map { field =>
          if(field.startsWith("-"))
            field.drop(1) -> -1
          else field -> 1
        }
        if order._1 == "account_id" || order._1 == "account_description" || order._1 == "transaction_type" || order._1 == "trade_date" || order._1 == "security_name" || order._1 == "price" || order._1 == "quantity" || order._1 == "commission" || order._1 == "currency"
      } yield order._1 -> BSONInteger(order._2)
      BSONDocument(sortBy :_*)
    }
  }


  private def getReport(request: Request[_]) = {
    request.queryString.get("report").map { fields =>
      val sortBy = for {
        order <- fields.map { field =>
          field -> 1
        }
        if order._1 == "short_term_trading" || order._1 == "cross_trading"
      } yield order._1
      BSONString(sortBy(0))
    }
  }

}







