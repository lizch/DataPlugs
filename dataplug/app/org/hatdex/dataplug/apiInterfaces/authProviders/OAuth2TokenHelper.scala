package org.hatdex.dataplug.apiInterfaces.authProviders

import javax.inject.Inject

import com.mohiva.play.silhouette.api._
import com.mohiva.play.silhouette.api.repositories.AuthInfoRepository
import com.mohiva.play.silhouette.api.util.HTTPLayer
import com.mohiva.play.silhouette.impl.exceptions.UnexpectedResponseException
import com.mohiva.play.silhouette.impl.providers.OAuth2Provider._
import com.mohiva.play.silhouette.impl.providers.{ OAuth2Info, OAuth2Provider, SocialProviderRegistry }
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import org.apache.commons.codec.binary.Base64
import play.api.cache.CacheApi
import play.api.{ Configuration, Logger }
import play.api.libs.ws.{ WSClient, WSResponse }

import scala.concurrent.duration._
import scala.concurrent.{ ExecutionContext, Future }
import scala.io.Codec
import scala.util.{ Failure, Success, Try }

class OAuth2TokenHelper @Inject() (
    configuration: Configuration,
    wsClient: WSClient,
    authInfoRepository: AuthInfoRepository,
    cache: CacheApi,
    socialProviderRegistry: SocialProviderRegistry) {

  /**
   * Refreshes the OAuth2Info token at the refreshURL.
   *
   * @param refreshToken The refresh token, as on OAuth2Info
   */
  def refresh(loginInfo: LoginInfo, refreshToken: String)(implicit ec: ExecutionContext): Option[Future[OAuth2Info]] = {
    val cachedToken = cache.get[OAuth2Info](s"oauth2:${loginInfo.providerKey}:${loginInfo.providerID}")
    cachedToken map { token =>
      Future.successful(token)
    } orElse {
      socialProviderRegistry.get[OAuth2Provider](loginInfo.providerID) match {
        case Some(p: OAuth2Provider) =>
          Logger.info(s"OAuth2Provider for $loginInfo, $refreshToken")
          implicit val provider = p
          val settings = configuration.underlying.as[OAuth2SettingsExtended](s"silhouette.${loginInfo.providerID}")
          settings.refreshURL.map({ url =>
            Logger.info(s"Refresh URL $url")
            val encodedAuth = Base64.encodeBase64(Codec.toUTF8(s"${p.settings.clientID}:${p.settings.clientSecret}")).toString
            val params = Seq(
              "client_id" -> p.settings.clientID,
              "client_secret" -> p.settings.clientSecret,
              "grant_type" -> "refresh_token",
              "refresh_token" -> refreshToken) ++ p.settings.scope.map({ "scope" -> _ })
            val body = params.map { p => p._1 + "=" + p._2 }.mkString("&")
            Logger.info(s"Querying token with URL $url,${settings.refreshHeaders}, BODY $body")
            val eventualToken = wsClient.url(url)
              .withHeaders("Authorization" -> encodedAuth)
              .withHeaders(settings.refreshHeaders.toSeq: _*)
              .post(body)
              .flatMap(resp => Future.fromTry(buildInfo(resp)))

            eventualToken.map {
              case fetchedToken =>
                cache.set(
                  s"oauth2:${loginInfo.providerKey}:${loginInfo.providerID}",
                  fetchedToken,
                  fetchedToken.expiresIn.map(t => t.seconds).getOrElse(0.seconds))
                fetchedToken
            }
          })
        case _ =>
          Logger.info(s"No OAuth2Provider for $loginInfo, $refreshToken")
          None
      }
    }
  }

  /**
   * Builds the OAuth2 info from response.
   *
   * @param response The response from the provider.
   * @return The OAuth2 info on success, otherwise a failure.
   */
  protected def buildInfo(response: WSResponse)(implicit provider: OAuth2Provider): Try[OAuth2Info] = {
    Logger.info(s"OAuth2 refresh response ${response.json}")
    response.json.validate[OAuth2Info].asEither.fold(
      error => Failure(new UnexpectedResponseException(InvalidInfoFormat.format(provider.id, error))),
      info => Success(info))
  }

  /**
   * The extended OAuth2 settings.
   *
   * @param authorizationURL    The authorization URL provided by the OAuth provider.
   * @param accessTokenURL      The access token URL provided by the OAuth provider.
   * @param redirectURL         The redirect URL to the application after a successful authentication on the OAuth
   *                            provider. The URL can be a relative path which will be resolved against the current
   *                            request's host.
   * @param apiURL              The URL to fetch the profile from the API. Can be used to override the default URL
   *                            hardcoded in every provider implementation.
   * @param refreshURL          The token refresh URL to the OAuth provider to refresh token if refresh token is available
   * @param refreshURL          The token refresh URL to the OAuth provider to refresh token if refresh token is available
   * @param clientID            The client ID provided by the OAuth provider.
   * @param clientSecret        The client secret provided by the OAuth provider.
   * @param scope               The OAuth2 scope parameter provided by the OAuth provider.
   * @param authorizationParams Additional params to add to the authorization request.
   * @param accessTokenParams   Additional params to add to the access token request.
   * @param customProperties    A map of custom properties for the different providers.
   */
  case class OAuth2SettingsExtended(
    authorizationURL: Option[String] = None,
    accessTokenURL: String,
    redirectURL: String,
    apiURL: Option[String] = None,
    refreshURL: Option[String] = None,
    refreshHeaders: Map[String, String] = Map(
      "Accept" -> "application/json",
      "Content-Type" -> "application/x-www-form-urlencoded"),
    clientID: String,
    clientSecret: String,
    scope: Option[String] = None,
    authorizationParams: Map[String, String] = Map.empty,
    accessTokenParams: Map[String, String] = Map.empty,
    customProperties: Map[String, String] = Map.empty)

}