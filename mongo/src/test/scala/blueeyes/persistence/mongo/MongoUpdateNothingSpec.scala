package blueeyes.persistence.mongo

import org.specs2.mutable.Specification
import MongoUpdateOperators._
import MongoFilterOperators._
import dsl._

import blueeyes.json._

import scalaz._
import Scalaz._

class MongoUpdateNothingSpec  extends Specification{
  "build valid json with MongoUpdateField" in {
    MongoUpdateNothing.asInstanceOf[MongoUpdate] |+| ("x" inc (1)) mustEqual ("x" inc (1))
  }
}
