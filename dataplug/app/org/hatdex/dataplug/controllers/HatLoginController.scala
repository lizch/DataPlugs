/*
 * Copyright (C) HAT Data Exchange Ltd - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 * Written by Andrius Aucinas <andrius.aucinas@hatdex.org>, 10 2016
 */

package org.hatdex.dataplug.controllers

import javax.inject.Inject

import com.mohiva.play.silhouette.api.exceptions.ProviderException
import com.mohiva.play.silhouette.api.util.Clock
import com.mohiva.play.silhouette.api.{ LoginEvent, Silhouette }
import com.mohiva.play.silhouette.impl.exceptions.IdentityNotFoundException
import com.mohiva.play.silhouette.impl.providers.SocialProviderRegistry
import org.hatdex.commonPlay.models.auth.forms.AuthForms
import org.hatdex.commonPlay.utils.MailService
import org.hatdex.dataplug.services.UserService
import org.hatdex.dataplug.utils.{ JwtPhataAuthenticatedAction, JwtPhataAwareAction, PhataAuthenticationEnvironment, SilhouettePhataAuthenticationController }
import org.hatdex.dataplug.views
import play.api.Logger
import play.api.i18n.{ Messages, MessagesApi }
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.ws.WSClient
import play.api.mvc._

import scala.concurrent.Future
import scala.concurrent.duration._

class HatLoginController @Inject() (
    val messagesApi: MessagesApi,
    mailService: MailService,
    silhouette: Silhouette[PhataAuthenticationEnvironment],
    wsClient: WSClient,
    configuration: play.api.Configuration,
    clock: Clock,
    userService: UserService,
    socialProviderRegistry: SocialProviderRegistry,
    tokenUserAwareAction: JwtPhataAwareAction,
    dataPlugViewSet: DataPlugViewSet,
    tokenUserAuthenticatedAction: JwtPhataAuthenticatedAction) extends SilhouettePhataAuthenticationController(silhouette, clock, configuration) {

  val hatProtocol = {
    configuration.getBoolean("provisioning.hatSecure") match {
      case Some(true) => "https://"
      case _          => "http://"
    }
  }

  val logger = Logger("HatLoginController")

  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  // HAT Login

  def authHat: Action[AnyContent] = tokenUserAwareAction.async { implicit request =>
    logger.info(s"logged in user ${request.maybeUser}")
    val authResult = request.maybeUser match {
      case Some(user) => for {
        authenticator <- env.authenticatorService.create(user.loginInfo)
        cookie <- env.authenticatorService.init(authenticator)
        result <- env.authenticatorService.embed(cookie, Redirect(dataPlugViewSet.indexRedirect))
      } yield {
        logger.debug(s"Logged in! ${user}")
        env.eventBus.publish(LoginEvent(user, request))
        result
      }
      case None => Future.failed(new IdentityNotFoundException("Couldn't find user"))
    }

    authResult.recover {
      case e: ProviderException =>
        logger.warn(Messages("auth.hatcredentials.incorrect"), e)
        Redirect(routes.HatLoginController.signinHat()).flashing("error" -> Messages("auth.hatcredentials.incorrect"))
    }
  }

  def signinHat: Action[AnyContent] = Action.async { implicit request =>
    AuthForms.signinHatForm.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(dataPlugViewSet.signIn(AuthForms.signinHatForm))),
      address => {
        val hatHost = address.stripPrefix("http://").stripPrefix("https://").replaceAll("[^A-Za-z0-9.]", "")

        val redirectUrl = routes.HatLoginController.authHat().absoluteURL(configuration.getBoolean("service.secure").getOrElse(false))

        val hatUri = wsClient.url(s"$hatProtocol$hatHost/hatlogin")
          .withQueryString("name" -> configuration.getString("service.name").get, "redirect" -> redirectUrl)

        val hatNameCookieAge = 90.days

        val result = Redirect(hatUri.uri.toString)
          .withCookies(Cookie("hatname", address, maxAge = Some(hatNameCookieAge.toSeconds.toInt)))
        Future.successful(result)

      })
  }

}