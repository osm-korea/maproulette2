package org.maproulette.models

import org.joda.time.DateTime
import play.api.libs.json.{Json, Reads, Writes}

/**
  * A comment can be associated to a Task, a comment contains the osm user that made the comment,
  * when it was created, the Task it is associated with, the actual comment and potentially the
  * action that was associated with the comment.
  *
  * @author cuthbertm
  */
case class Comment(id:Long,
                   osm_id:Long,
                   osm_username:String,
                   task_id:Long,
                   created:DateTime,
                   comment:String,
                   actionId:Option[Long]=None)

object Comment {
  implicit val commentWrites: Writes[Comment] = Json.writes[Comment]
  implicit val commentReads: Reads[Comment] = Json.reads[Comment]
}
