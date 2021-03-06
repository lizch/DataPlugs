/*
 * Copyright (C) 2016 HAT Data Exchange Ltd - All Rights Reserved
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * Written by Andrius Aucinas <andrius.aucinas@hatdex.org>, 10 2016
 */

package org.hatdex.dataplug.controllers

import javax.inject.Inject

import org.hatdex.dataplug.actors.IoExecutionContext
import org.hatdex.dataplug.apiInterfaces.models.JsonProtocol.endpointStatusFormat
import org.hatdex.dataplug.models.User
import org.hatdex.dataplug.services.{ DataPlugEndpointService, DataplugSyncerActorManager }
import org.hatdex.dataplug.utils.{ JwtPhataAuthenticatedAction, JwtPhataAwareAction }
import org.hatdex.hat.api.models.ErrorMessage
import play.api.i18n.MessagesApi
import play.api.libs.json.{ JsValue, Json }
import play.api.{ Configuration, Logger }
import play.api.mvc._
import org.hatdex.hat.api.json.HatJsonFormats.errorMessage

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ ExecutionContext, Future }

class Api @Inject() (
    messagesApi: MessagesApi,
    configuration: Configuration,
    tokenUserAwareAction: JwtPhataAwareAction,
    tokenUserAuthenticatedAction: JwtPhataAuthenticatedAction,
    dataPlugEndpointService: DataPlugEndpointService,
    syncerActorManager: DataplugSyncerActorManager) extends Controller {

  protected val ioEC: ExecutionContext = IoExecutionContext.ioThreadPool
  protected val provider: String = configuration.getString("service.provider").getOrElse("")

  protected val logger: Logger = Logger(this.getClass)

  def tickle: Action[AnyContent] = tokenUserAuthenticatedAction.async { implicit request =>
    syncerActorManager.runPhataActiveVariantChoices(request.identity.userId) map { _ =>
      Ok(Json.toJson(Map("message" -> "Tickled")))
    }
  }

  def status: Action[AnyContent] = tokenUserAuthenticatedAction.async { implicit request =>
    // Check if the user has the required social profile linked
    request.identity.linkedUsers.find(_.providerId == provider) map { _ =>
      val result = for {
        choices <- syncerActorManager.currentProviderApiVariantChoices(request.identity, provider)(ioEC) if choices.exists(_.active)
        apiEndpointStatuses <- dataPlugEndpointService.listCurrentEndpointStatuses(request.identity.userId)
      } yield {
        Ok(Json.toJson(apiEndpointStatuses))
      }

      // In case fetching current endpoint statuses failed, assume the issue came from refreshing data from the provider
      result recover {
        case _ => Forbidden(
          Json.toJson(ErrorMessage(
            "Forbidden",
            "The user is not authorized to access remote data - has Access Token been revoked?")))
      }
    } getOrElse {
      Future.successful(Forbidden(Json.toJson(ErrorMessage("Forbidden", s"Required social profile ($provider) not connected"))))
    }
  }

  def permissions: Action[AnyContent] = tokenUserAuthenticatedAction.async { implicit request =>
    Future.successful(InternalServerError(Json.toJson(Map("message" -> "Not Implemented", "error" -> "Not implemented"))))
  }

  def adminDisconnect(hat: Option[String]): Action[AnyContent] = Action.async { implicit request =>
    val adminSecret = configuration.getString("service.admin.secret").getOrElse("")

    (request.headers.get("x-auth-token"), hat) match {
      case (Some(authToken), Some(hatDomain)) =>
        if (authToken == adminSecret) {
          val eventualResult = for {
            variantChoices <- syncerActorManager.currentProviderStaticApiVariantChoices(hatDomain, provider)(ioEC)
            apiEndpointStatuses <- dataPlugEndpointService.listCurrentEndpointStatuses(hatDomain)
          } yield {
            if (apiEndpointStatuses.nonEmpty) {
              logger.debug(s"Got choices for $hatDomain to disconnect: $variantChoices")
              syncerActorManager.updateApiVariantChoices(User("", hatDomain, List()), variantChoices.map(_.copy(active = false))) map { _ =>
                Ok(Json.obj("message" -> s"Plug disconnected for $hatDomain"))
              }
            }
            else {
              Future.successful(BadRequest(jsonErrorResponse("Bad Request", s"Plug already disconnected for $hatDomain")))
            }
          }

          eventualResult.flatMap(r => r).recover {
            case e =>
              logger.error(s"$provider API cannot be accessed: ${e.getMessage}", e)
              BadRequest(jsonErrorResponse("Bad Request", s"Cannot find information for $hatDomain"))
          }
        }
        else {
          Future.successful(Unauthorized(jsonErrorResponse("Unauthorized", "Authentication token invalid")))
        }
      case (None, _) =>
        Future.successful(BadRequest(jsonErrorResponse("Bad Request", "Authentication token missing")))
      case (Some(_), None) =>
        Future.successful(BadRequest(jsonErrorResponse("Bad Request", "HAT address not specified")))
    }
  }

  private def jsonErrorResponse(error: String, message: String): JsValue =
    Json.obj("error" -> error, "message" -> message)
}
