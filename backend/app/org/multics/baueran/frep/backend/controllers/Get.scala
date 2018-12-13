package org.multics.baueran.frep.backend.controllers

import scala.collection.mutable.ListBuffer
import javax.inject._
import play.api.mvc._
import io.circe.syntax._

import org.multics.baueran.frep.backend.repertory._
import org.multics.baueran.frep.shared._

import org.multics.baueran.frep.backend.models.Users
import org.multics.baueran.frep.backend.dao.UsersDao
import org.multics.baueran.frep.backend.db.db.DBContext
import org.multics.baueran.frep.shared.Defs._

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */

@Singleton
class Get @Inject()(cc: ControllerComponents, dbContext: DBContext) extends AbstractController(cc) {
  private val users = new UsersDao(dbContext)

  def index() = Action { request: Request[AnyContent] =>
    if (authorizedRequestCookies(request) == List.empty)
      Redirect(serverUrl() + "/assets/html/public/index.html")
    else
      Redirect(serverUrl() + "/assets/html/private/index.html")
  }

  def get(id: Long) = Action { request: Request[AnyContent] =>
    val user = users.get(id)
    val username = user.head.realname

    Ok("test called: " + username)
  }

  def insert(id: Long) = Action { request: Request[AnyContent] =>
    import java.util.Date
    import java.sql.Timestamp
    import java.sql.Date
    import java.time.LocalDate

    // https://stackoverflow.com/questions/32133033/java-jdbc-how-to-insert-current-date-but-formatted-into-database
    // https://stackoverflow.com/questions/32202155/java-simpledateformat-correct-format-for-postgres-timestamp-with-timezone-da
    val today = java.sql.Date.valueOf(LocalDate.now)

    val new_user = Users(id, "baueran", "md5_hash", "Andi Bauer", "email@email.com", "de", None, None, //      student_until: Option[Date] = None,
      Some(today), None)

    users.insert(new_user)

    Ok("inserted!")
  }

  /**
    * If method is called, it is expected that the browser has sent a cookie with the
    * request.  The method then checks, if this cookie authenticates the user for access
    * of further application functionality.
    */
  def authenticate() = Action { request: Request[AnyContent] =>
    authorizedRequestCookies(request) match {
      case Nil => BadRequest("Not authorized.")
      case cookies => Ok.withCookies(cookies:_*)
    }
  }

  def availableReps() = Action { request: Request[AnyContent] =>
    val availRepositories = RepDatabase.availableRepertories()

    if (authorizedRequestCookies(request) == List.empty)
      Ok(availRepositories.filter(r => r.access == RepAccess.Default || r.access == RepAccess.Public).asJson.toString())
    else
      Ok(availRepositories.asJson.toString())
  }

  def repertorise(repertoryAbbrev: String, symptom: String) = Action { request: Request[AnyContent] =>
    RepDatabase.repertory(repertoryAbbrev) match {
      case Some(loadedRepertory) =>
        val resultRubrics = loadedRepertory.findRubrics(symptom).filter(_.chapterId >= 0)

        if (resultRubrics.size <= 0)
          BadRequest("No results found.")
        else {
          val resultSetTooLarge = resultRubrics.size > 100
          var resultSet = ListBuffer[CaseRubric]()

          for (i <- 0 to math.min(100, resultRubrics.size) - 1) {
            val rubric = resultRubrics(i)
            (loadedRepertory.chapter(rubric.chapterId): Option[Chapter]) match {
              case Some(chapter) => {
                val remedyWeightTuples = rubric.remedyWeightTuples(loadedRepertory.remedies, loadedRepertory.rubricRemedies)
                val response = ("(" + rubric.id + ") " + rubric.fullPath + ": " + rubric.path + ", " + rubric.text + ": "
                               + remedyWeightTuples.map { case (r, w) => r.nameAbbrev + "(" + w + ")" }.mkString(", "))
                // println(response)

                // case class CaseRubric(rubric: Rubric, repertoryAbbrev: String, rubricWeight: Int, weightedRemedies: Map[Remedy, Integer])
                // contains sth. like this: (68955) Bladder, afternoon: None, None: Chel.(2), Sulph.(2), Lil-t.(1), Sabad.(1), Petr.(1), Nux-v.(2), Merc.(1), Hyper.(1), Ferr.(1), Equis.(1), Cic.(1), Chin-s.(1), Bell.(1), Indg.(1), Aloe(1), Lyc.(3), Spig.(1), Lith-c.(1), Sep.(1), Coc-c.(1), Chlol.(1), Alumn.(1), Bov.(1)
                resultSet += CaseRubric(rubric, repertoryAbbrev, 1,
                  remedyWeightTuples.foldLeft(Map[Remedy, Integer]()) { (e1, e2) => e1 + (e2._1 -> e2._2) })
              }
              case None => ;
            }
          }

          Ok(resultSet.asJson.toString())
        }
      case None => BadRequest(s"Repertory $repertoryAbbrev not found. Available repertories: " + RepDatabase.availableRepertories().map(_.abbrev).mkString(", "))
    }
  }
}