package org.multics.baueran.frep.backend.controllers

import javax.inject._
import play.api.mvc._
import play.api.Logger
import io.circe.syntax._
import org.multics.baueran.frep.backend.dao.{CazeDao, FileDao, RepertoryDao}
import org.multics.baueran.frep.backend.repertory._
import org.multics.baueran.frep.shared._
import org.multics.baueran.frep.backend.dao.MemberDao
import org.multics.baueran.frep.backend.db.db.DBContext
import org.multics.baueran.frep.shared.Defs._
import Defs.maxNumberOfResults

/**
 * This controller creates an `Action` to handle HTTP requests to the application's home page.
 */

@Singleton
class Get @Inject()(cc: ControllerComponents, dbContext: DBContext) extends AbstractController(cc) with ServerUrl {
  cazeDao = new CazeDao(dbContext)
  fileDao = new FileDao(dbContext)
  memberDao = new MemberDao(dbContext)

  RepDatabase.setup(dbContext)

  def index() = Action { request: Request[AnyContent] =>
    if (getRequestCookies(request) == List.empty)
      Redirect(serverUrl(request) + "/assets/html/index.html")
    else
      Redirect(serverUrl(request) + "/assets/html/private/index.html")
  }

  def acceptCookies() = Action { request: Request[AnyContent] =>
    Redirect(serverUrl(request))
        .withCookies(Cookie(CookieFields.cookiePopupAccepted.toString, "1", httpOnly = false))
  }

  /**
    * If method is called, it is expected that the browser has sent a cookie with the
    * request.  The method then checks, if this cookie authenticates the user for access
    * of further application functionality.
    */
  def authenticate() = Action { request: Request[AnyContent] =>
    val cookies = getRequestCookies(request)

    getCookieValue(cookies, CookieFields.id.toString) match {
      case Some(memberIdStr) => {
        doesUserHaveAuthorizedCookie(request) match {
          case Right(_) =>
            Ok(memberIdStr).withCookies(cookies.map({ c => Cookie(name = c.name, value = c.value, httpOnly = false) }):_*)
          case _ =>
            val errStr = s"Get: authenticate(): Not authorized: user not authorized to login."
            Logger.error(errStr)
            BadRequest(errStr)
        }
      }
      case _ =>
        val errStr = s"Get: authenticate(): Not authorized: user not in database."
        Logger.error(errStr)
        BadRequest(errStr)
    }
  }

  def availableReps() = Action { request: Request[AnyContent] =>
    val dao = new RepertoryDao(dbContext)

    if (getRequestCookies(request) == List.empty)
      Ok(dao.getAllAvailableRepertoryInfos().filter(r => r.access == RepAccess.Default || r.access == RepAccess.Public).asJson.toString())
    else
      Ok(dao.getAllAvailableRepertoryInfos().asJson.toString())
  }

  /**
    * Won't actually return files with associated cases, but a list of tuples, (file ID, file header).
    */
  def availableFiles(memberId: Int) = Action { request: Request[AnyContent] =>
    doesUserHaveAuthorizedCookie(request) match {
      case Left(err) =>
        val errStr = "Get: availableFiles(): availableFiles() failed: " + err
        Logger.error(errStr)
        BadRequest(errStr)
      case Right(_) =>
        val dao = new FileDao(dbContext)
        val dbFiles = dao.getDbFilesForMember(memberId)
        Ok(dbFiles.map(dbFile => (dbFile.id, dbFile.header)).asJson.toString)
    }
  }

  def getFile(fileId: String) = Action { request: Request[AnyContent] =>
    doesUserHaveAuthorizedCookie(request) match {
      case Left(err) =>
        Logger.error(err)
        BadRequest(err)
      case Right(_) => {
        val dao = new FileDao(dbContext)
        dao.getFIle(fileId.toInt) match {
          case file :: Nil =>
            Ok(file.asJson.toString())
          case _ =>
            val errStr = "Get: getFile() returned nothing"
            Logger.error(errStr)
            BadRequest(errStr)
        }
      }
    }
  }

  def getCase(memberId: Int, caseId: String) = Action { request: Request[AnyContent] =>
    doesUserHaveAuthorizedCookie(request) match {
      case Right(_) if (caseId.forall(_.isDigit)) => {
        val dao = new CazeDao(dbContext)
        dao.get(caseId.toInt) match {
          case Right(caze) if (caze.member_id == memberId) =>
            Ok(caze.asJson.toString())
          case _ =>
            val errStr = "Get: getCase() failed: DB returned nothing."
            Logger.error(errStr)
            BadRequest(errStr)
        }
      }
      case _ =>
        BadRequest("Get: getCase() failed.")
    }
  }

  def availableCasesForFile(fileId: String) = Action { request: Request[AnyContent] =>
    doesUserHaveAuthorizedCookie(request) match {
      case Left(err) =>
        Logger.error(err)
        BadRequest(err)
      case Right(_) => {
        Ok(fileDao
          .getCasesFromFile(fileId)
          .asJson
          .toString())
      }
    }
  }

  /**
   * Returns basically a list of pairs with single entries like
   *
   *   (file_description, Some(case_id), Some("[date] 'header'")),
   *
   * but JSON-encoded, of course.  (Descr, None, None) if no cases
   * are associated.
   */
  def fileOverview(fileId: String) = Action { request: Request[AnyContent] =>
    doesUserHaveAuthorizedCookie(request) match {
      case Right(_) if (fileId.forall(_.isDigit)) =>
        val results = fileDao
          .getFIle(fileId.toInt)
          .flatMap(f =>
            f.cazes match {
              case Nil => List((f.description, None, None))
              case cazes => cazes.map(c => (f.description, Some(c.id), Some(s"[${c.date.take(10)}] '${c.header}'")))
            }
          )
        Ok(results.asJson.toString())
      case Left(err) =>
        Logger.error(err)
        BadRequest(err)
      case _ =>
        val err = "Get: fileOverview failed."
        Logger.error(err)
        BadRequest(err)
    }
  }

  def repertorise(repertoryAbbrev: String, symptom: String) = Action { request: Request[AnyContent] =>
    val dao = new RepertoryDao(dbContext)
    val results: List[CaseRubric] = dao.lookupSymptom(repertoryAbbrev, symptom)

    if (results.size == 0) {
      val errStr = s"Get: repertorise(${repertoryAbbrev}, ${symptom}): no results found"
      Logger.warn(errStr)
      BadRequest(errStr)
    }
    else {
      Logger.debug(s"Get: repertorise(${repertoryAbbrev}, ${symptom}): #results: ${results.size}.")
      Ok(results.take(maxNumberOfResults).asJson.toString())
    }
  }

}
