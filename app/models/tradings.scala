package models

import org.jboss.netty.buffer._
import org.joda.time.DateTime
import play.api.data._
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.data.validation.Constraints._

import reactivemongo.bson._
import reactivemongo.bson.handlers._


case class Trading(
                      id: Option[BSONObjectID],
                      account_id: String,
                      account_description: String,
                      transaction_type: String,
                      trade_date: Option[DateTime],
                      security_name: String,
                      price: Double,
                      commission: Double,
                      quantity: Double,
                      currency:String
                      )
// Turn off your mind, relax, and float downstream
// It is not dying...
object Trading {
  implicit object TradingBSONReader extends BSONReader[Trading] {
    def fromBSON(document: BSONDocument) :Trading = {
      val doc = document.toTraversable
      Trading(
        doc.getAs[BSONObjectID]("_id"),
        doc.getAs[BSONString]("account_id").get.value,
        doc.getAs[BSONString]("account_description").get.value,
        doc.getAs[BSONString]("transaction_type").get.value,
        doc.getAs[BSONDateTime]("trade_date").map(dt => new DateTime(dt.value)),
        doc.getAs[BSONString]("security_name").get.value,
        doc.getAs[BSONDouble]("price").get.value,
        doc.getAs[BSONDouble]("quantity").get.value,
        doc.getAs[BSONDouble]("commission").get.value,
        doc.getAs[BSONString]("currency").get.value
      )
    }
  }
  implicit object TradingBSONWriter extends BSONWriter[Trading] {
    def toBSON(trading: Trading) = {
      BSONDocument(
        "_id" -> trading.id.getOrElse(BSONObjectID.generate),
        "account_id" -> BSONString(trading.account_id),
        "account_description" -> BSONString(trading.account_description),
        "transaction_type" -> BSONString(trading.transaction_type),
        "trade_date" -> trading.trade_date.map(date => BSONDateTime(date.getMillis)),
        "security_name" -> BSONString(trading.security_name),
        "price" -> BSONDouble(trading.price),
        "quantity" -> BSONDouble(trading.quantity),
        "commission" -> BSONDouble(trading.commission),
        "currency" -> BSONString(trading.currency)
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
      "account_description" -> text,
      "transaction_type" -> nonEmptyText,
      "trade_date" -> optional(of[Long]),
      "security_name" -> nonEmptyText,
      "price" -> of(doubleFormat),
      "quantity" -> of(doubleFormat),
      "commission" -> of(doubleFormat),
      "currency" -> text

    ) { (id, account_id, account_name, transaction_type, trade_date, security_name, price, quantity, commission,currency) =>
      Trading(
        id.map(new BSONObjectID(_)),
        account_id,
        account_name,
        transaction_type,
        trade_date.map(new DateTime(_)),
        security_name,
        price,
        quantity,
        commission,
      currency)
    } { trading =>
      Some(
        (trading.id.map(_.stringify),
          trading.account_id,
          trading.account_description,
          trading.transaction_type,
          trading.trade_date.map(_.getMillis),
          trading.security_name,
          trading.price,
          trading.quantity,
          trading.commission,
          trading.currency))
    })
}