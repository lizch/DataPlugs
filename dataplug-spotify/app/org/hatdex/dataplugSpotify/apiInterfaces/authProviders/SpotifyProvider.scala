package org.hatdex.dataplugSpotify.apiInterfaces.authProviders

import com.mohiva.play.silhouette.api.LoginInfo
import com.mohiva.play.silhouette.api.crypto.Base64
import com.mohiva.play.silhouette.api.util.HTTPLayer
import com.mohiva.play.silhouette.impl.exceptions.ProfileRetrievalException
import com.mohiva.play.silhouette.impl.providers._
import org.hatdex.dataplugSpotify.apiInterfaces.authProviders.SpotifyProvider._
import play.api.http.HeaderNames._
import play.api.libs.json.{ JsArray, JsValue }

import scala.concurrent.Future

/**
 * Base Fitbit OAuth2 Provider.
 *
 * @see https://dev.fitbit.com/docs/oauth2/
 */
trait BaseSpotifyProvider extends OAuth2Provider {

  /**
   * The content type to parse a profile from.
   */
  override type Content = JsValue

  /**
   * The provider ID.
   */
  override val id = ID

  /**
   * Defines the URLs that are needed to retrieve the profile data.
   */
  override protected val urls = Map("api" -> settings.apiURL.getOrElse(API))

  override protected val headers: Seq[(String, String)] = Seq(
    "Authorization" -> "Basic ".concat(Base64.encode(s"${settings.clientID}:${settings.clientSecret}")),
    "Content-Type" -> "application/x-www-form-urlencoded")

  /**
   * Builds the social profile.
   *
   * @param authInfo The auth info received from the provider.
   * @return On success the build social profile, otherwise a failure.
   */
  override protected def buildProfile(authInfo: OAuth2Info): Future[Profile] = {
    httpLayer.url(urls("api")).withHeaders(AUTHORIZATION -> s"Bearer ${authInfo.accessToken}").get().flatMap { response =>
      val json = response.json
      (json \ "errors").asOpt[JsArray] match {
        case Some(errors) =>
          val error = errors.head.get
          val errorType = (error \ "errorType").as[String]
          val errorMsg = (error \ "message").as[String]

          throw new ProfileRetrievalException(SpecifiedProfileError.format(id, errorType, errorMsg))
        case _ => profileParser.parse(json, authInfo)
      }
    }
  }
}

/**
 * The profile parser for the common social profile.
 */
class SpotifyProfileParser extends SocialProfileParser[JsValue, CommonSocialProfile, OAuth2Info] {

  /**
   * Parses the social profile.
   *
   * @param json     The content returned from the provider.
   * @param authInfo The auth info to query the provider again for additional data.
   * @return The social profile from given result.
   */
  override def parse(json: JsValue, authInfo: OAuth2Info) = Future.successful {
    // https://developer.spotify.com/web-api/get-current-users-profile/
    val userID = (json \ "id").as[String]
    val fullName = (json \ "display_name").asOpt[String]
    val email = (json \ "email").asOpt[String]

    CommonSocialProfile(
      loginInfo = LoginInfo(ID, userID),
      fullName = fullName,
      email = email)
  }
}

/**
 * The Google OAuth2 Provider.
 *
 * @param httpLayer     The HTTP layer implementation.
 * @param stateProvider  The state provider implementation.
 * @param settings      The provider settings.
 */
class SpotifyProvider(
    protected val httpLayer: HTTPLayer,
    protected val stateProvider: OAuth2StateProvider,
    val settings: OAuth2Settings)
  extends BaseSpotifyProvider with CommonSocialProfileBuilder {

  /**
   * The type of this class.
   */
  type Self = SpotifyProvider

  /**
   * The profile parser implementation.
   */
  val profileParser = new SpotifyProfileParser

  /**
   * Gets a provider initialized with a new settings object.
   *
   * @param f A function which gets the settings passed and returns different settings.
   * @return An instance of the provider initialized with new settings.
   */
  def withSettings(f: (Settings) => Settings) = new SpotifyProvider(httpLayer, stateProvider, f(settings))
}

/**
 * The companion object.
 */
object SpotifyProvider {

  /**
   * The error messages.
   */
  val SpecifiedProfileError = "[Silhouette][%s] Error retrieving profile information. Error type: %s, message: %s"

  /**
   * The Google constants.
   */
  val ID = "spotify"
  val API = "https://api.spotify.com/v1/me"
}