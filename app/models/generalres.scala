package models

import org.jboss.netty.buffer._
import org.joda.time.DateTime
import play.api.data._
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.data.validation.Constraints._

import reactivemongo.bson._
import reactivemongo.bson.handlers._

case class GeneralRE(
                    id: Option[BSONObjectID],
                    account_id: String,
                    account_name: String,
                    transaction_type: String,
                    trade_date: String,
                    security_name: String
                    )
// Turn off your mind, relax, and float downstream
// It is not dying...
object GeneralRE {
  implicit object GeneralREBSONReader extends BSONReader[GeneralRE] {
    def fromBSON(document: BSONDocument) :GeneralRE = {
      val doc = document.toTraversable
      GeneralRE(
        doc.getAs[BSONObjectID]("_id"),
        doc.getAs[BSONString]("account_id").get.value,
        doc.getAs[BSONString]("account_name").get.value,
        doc.getAs[BSONString]("transaction_type").get.value,
        doc.getAs[BSONString]("trade_date").get.value,
        doc.getAs[BSONString]("security_name").get.value)
    }
  }
  implicit object GeneralREBSONWriter extends BSONWriter[GeneralRE] {
    def toBSON(generalre: GeneralRE) = {
      BSONDocument(
        "_id" -> generalre.id.getOrElse(BSONObjectID.generate),
        "account_id" -> BSONString(generalre.account_id),
        "account_name" -> BSONString(generalre.account_name),
        "transaction_type" -> BSONString(generalre.transaction_type),
        "trade_date" -> BSONString(generalre.trade_date),
        "security_name" -> BSONString(generalre.security_name)
      )
    }
  }
  val form = Form(
    mapping(
      "id" -> optional(of[String] verifying pattern(
        """[a-fA-F0-9]{24}""".r,
        "constraint.objectId",
        "error.objectId")),
      "account_id" -> nonEmptyText,
      "account_name" -> text,
      "transaction_type" -> nonEmptyText,
      "trade_date" -> nonEmptyText,
      "security_name" -> nonEmptyText
    ) { (id, account_id, account_name, transaction_type, trade_date, security_name) =>
      GeneralRE(
        id.map(new BSONObjectID(_)),
        account_id,
        account_name,
        transaction_type,
        trade_date,
        security_name)
    } { generalre =>
      Some(
        (generalre.id.map(_.stringify),
          generalre.account_id,
          generalre.account_name,
          generalre.transaction_type,
          generalre.trade_date,
          generalre.security_name))
    })
}