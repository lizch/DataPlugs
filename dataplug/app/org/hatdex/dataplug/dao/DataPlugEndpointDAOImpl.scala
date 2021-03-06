/*
 * Copyright (C) 2016 HAT Data Exchange Ltd - All Rights Reserved
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * Written by Andrius Aucinas <andrius.aucinas@hatdex.org>, 10 2016
 */

package org.hatdex.dataplug.dao

import javax.inject.{ Inject, Singleton }

import anorm.JodaParameterMetaData._
import anorm.{ RowParser, _ }
import org.hatdex.dataplug.actors.IoExecutionContext
import org.hatdex.dataplug.apiInterfaces.models._
import play.api.db.{ Database, NamedDatabase }
import play.api.libs.json.Json

import scala.concurrent._

/**
 * Give access to the user object.
 */
@Singleton
class DataPlugEndpointDAOImpl @Inject() (@NamedDatabase("default") db: Database) extends DataPlugEndpointDAO {
  implicit val ec: ExecutionContext = IoExecutionContext.ioThreadPool

  implicit val apiEndpointCallColumn: Column[ApiEndpointCall] = {
    import JsonProtocol.endpointCallFormat
    Column.nonNull { (value, _) =>
      Right(Json.parse(value.toString).as[ApiEndpointCall])
    }
  }

  implicit val apiEndpointCallColumnOptional: Column[Option[ApiEndpointCall]] = Column.columnToOption[ApiEndpointCall]

  implicit private val apiEndpointParser: RowParser[ApiEndpoint] =
    Macro.parser[ApiEndpoint](
      "dataplug_endpoint.name",
      "dataplug_endpoint.description",
      "dataplug_endpoint.details")

  implicit private val apiEndpointVariantParser: RowParser[ApiEndpointVariant] =
    Macro.parser[ApiEndpointVariant](
      "dataplug_user.dataplug_endpoint",
      "dataplug_user.endpoint_variant",
      "dataplug_user.endpoint_variant_description",
      "dataplug_user.endpoint_configuration")

  implicit private val apiEndpointVariantWithPhataParser: RowParser[(String, ApiEndpointVariant)] =
    SqlParser.str("dataplug_user.phata") ~
      apiEndpointVariantParser map {
        case phata ~ endpointVariant =>
          (phata, endpointVariant)
      }

  implicit private val apiEndpointStatusParser: RowParser[ApiEndpointStatus] =
    Macro.parser[ApiEndpointStatus](
      "log_dataplug_user.phata",
      "log_dataplug_user.dataplug_endpoint",
      "log_dataplug_user.endpoint_configuration",
      "log_dataplug_user.created",
      "log_dataplug_user.successful",
      "log_dataplug_user.message")

  /**
   * Retrieves a user that matches the specified ID.
   *
   * @param phata The user phata.
   * @return The list of retrieved endpoints, as a String value
   */
  def retrievePhataEndpoints(phata: String): Future[Seq[ApiEndpointVariant]] = {
    Future {
      blocking {
        db.withConnection { implicit connection =>
          SQL(
            """
              | SELECT * FROM dataplug_endpoint
              | JOIN dataplug_user ON dataplug_user.dataplug_endpoint = dataplug_endpoint.name
              | WHERE dataplug_user.phata = {phata}
              |   AND active = TRUE
            """.stripMargin)
            .on('phata -> phata)
            .as(apiEndpointVariantParser.*)
        }
      }
    }
  }

  /**
   * Retrieves a list of all registered endpoints together with corresponding user PHATAs
   *
   * @return The list of tuples of PHATAs and corresponding endpoints
   */
  def retrieveAllEndpoints: Future[Seq[(String, ApiEndpointVariant)]] = {
    Future {
      blocking {
        db.withConnection { implicit connection =>
          SQL(
            """
              | SELECT * FROM dataplug_endpoint
              | JOIN dataplug_user ON dataplug_user.dataplug_endpoint = dataplug_endpoint.name
              | WHERE active = TRUE
            """.stripMargin)
            .as(apiEndpointVariantWithPhataParser.*)
        }
      }
    }
  }

  /**
   * Activates an API endpoint for a user
   *
   * @param phata The user phata.
   * @param plugName The plug endpoint name.
   */
  def activateEndpoint(phata: String, plugName: String, variant: Option[String], configuration: Option[ApiEndpointCall]): Future[Unit] = {
    import JsonProtocol.endpointCallFormat
    Future {
      blocking {
        db.withConnection { implicit connection =>
          SQL(
            """
              | INSERT INTO dataplug_user
              |   (phata, dataplug_endpoint, endpoint_variant, endpoint_variant_description, endpoint_configuration, active)
              | VALUES ({phata}, {endpoint}, {variant}, {variantDescription}, {configuration}::JSONB, TRUE)
              | ON CONFLICT (phata, dataplug_endpoint, endpoint_variant) DO UPDATE
              |   SET
              |     endpoint_configuration = {configuration}::JSONB,
              |     active = TRUE
            """.stripMargin)
            .on(
              'phata -> phata,
              'endpoint -> plugName,
              'variant -> variant,
              'variantDescription -> Option.empty[String],
              'configuration -> configuration.map(c => Json.toJson(c)).map(_.toString))
            .executeInsert()
        }
      }
    }
  }

  /**
   * Deactivates API endpoint for a user
   *
   * @param phata The user phata.
   * @param plugName The plug endpoint name.
   */
  def deactivateEndpoint(phata: String, plugName: String, variant: Option[String]): Future[Unit] = {
    Future {
      blocking {
        db.withConnection { implicit connection =>
          SQL(
            """
              | UPDATE dataplug_user
              |   SET active = FALSE
              |   WHERE phata = {phata}
              |     AND dataplug_endpoint = {endpoint}
              |     AND endpoint_variant = {variant}
            """.stripMargin)
            .on('phata -> phata, 'endpoint -> plugName, 'variant -> variant)
            .executeUpdate()
        }
      }
    }
  }

  /**
   * Saves endpoint status for a given phata and plug endpoint
   *
   * @param phata The user phata.
   * @param plugName The plug endpoint name.
   * @param endpoint Endpoint configuration
   */
  def saveEndpointStatus(phata: String, endpointStatus: ApiEndpointStatus): Future[Unit] = {
    Future {
      blocking {
        import JsonProtocol.endpointCallFormat
        db.withConnection { implicit connection =>
          SQL(
            """
              | INSERT INTO log_dataplug_user
              |   (phata, dataplug_endpoint, endpoint_configuration, endpoint_variant, created, successful, message)
              | VALUES ({phata}, {dataplugEndpoint}, {endpoint}::JSONB, {variant}, {created}, {successful}, {message})
            """.stripMargin)
            .on(
              'phata -> phata,
              'dataplugEndpoint -> endpointStatus.apiEndpoint.endpoint.name,
              'endpoint -> Json.toJson(endpointStatus.endpointCall).toString,
              'variant -> endpointStatus.apiEndpoint.variant,
              'created -> endpointStatus.timestamp,
              'successful -> endpointStatus.successful,
              'message -> endpointStatus.message)
            .executeInsert()
        }
      }
    }
  }

  /**
   * Retrieves most recent endpoint status for a given phata and plug endpoint
   *
   * @param phata The user phata.
   * @param plugName The plug endpoint name.
   * @return The available API endpoint configuration
   */
  def retrieveCurrentEndpointStatus(phata: String, plugName: String, variant: Option[String]): Future[Option[ApiEndpointStatus]] = {
    Future {
      blocking {
        db.withConnection { implicit connection =>
          SQL(
            """
              | SELECT * FROM log_dataplug_user
              |   JOIN (
              |       SELECT phata, dataplug_endpoint, endpoint_variant, MAX(created) AS created
              |         FROM log_dataplug_user
              |         GROUP BY (phata, dataplug_endpoint, endpoint_variant)) ld2
              |     ON log_dataplug_user.phata = ld2.phata
              |       AND log_dataplug_user.dataplug_endpoint=ld2.dataplug_endpoint
              |       AND log_dataplug_user.endpoint_variant = ld2.endpoint_variant
              |       AND log_dataplug_user.created = ld2.created
              |   JOIN dataplug_user
              |     ON dataplug_user.dataplug_endpoint = log_dataplug_user.dataplug_endpoint
              |       AND dataplug_user.phata = log_dataplug_user.phata
              |       AND dataplug_user.endpoint_variant = log_dataplug_user.endpoint_variant
              |   JOIN dataplug_endpoint ON dataplug_user.dataplug_endpoint = dataplug_endpoint.name
              | WHERE dataplug_user.phata = {phata}
              |     AND dataplug_user.dataplug_endpoint = {dataplug_endpoint}
              |     AND dataplug_user.endpoint_variant = {variant}
              | ORDER BY created DESC
              | LIMIT 1
            """.stripMargin)
            .on(
              'phata -> phata,
              'dataplug_endpoint -> plugName,
              'variant -> variant)
            .as(apiEndpointStatusParser.singleOpt)
        }
      }
    }
  }

  /**
   * Fetches endpoint status for a given phata and plug endpoint
   *
   * @param phata The user phata.
   * @return The available API endpoint configurations
   */
  def listCurrentEndpointStatuses(phata: String): Future[Seq[ApiEndpointStatus]] = {
    Future {
      blocking {
        db.withConnection { implicit connection =>
          SQL(
            """
              | SELECT * FROM log_dataplug_user
              |   JOIN (
              |       SELECT phata, dataplug_endpoint, endpoint_variant, MAX(created) AS created
              |         FROM log_dataplug_user
              |         GROUP BY (phata, dataplug_endpoint, endpoint_variant)) ld2
              |     ON log_dataplug_user.phata = ld2.phata
              |       AND log_dataplug_user.dataplug_endpoint=ld2.dataplug_endpoint
              |       AND log_dataplug_user.endpoint_variant = ld2.endpoint_variant
              |       AND log_dataplug_user.created = ld2.created
              |   JOIN dataplug_user
              |     ON dataplug_user.dataplug_endpoint = log_dataplug_user.dataplug_endpoint
              |       AND dataplug_user.phata = log_dataplug_user.phata
              |       AND dataplug_user.endpoint_variant = log_dataplug_user.endpoint_variant
              |   JOIN dataplug_endpoint ON dataplug_user.dataplug_endpoint = dataplug_endpoint.name
              | WHERE dataplug_user.phata = {phata}
            """.stripMargin)
            .on('phata -> phata)
            .as(apiEndpointStatusParser.*)
        }
      }
    }
  }

  /**
   * Retrieves most recent endpoint status for a given phata and plug endpoint
   *
   * @param phata The user phata.
   * @param plugName The plug endpoint name.
   * @return The available API endpoint configuration
   */
  def retrieveLastSuccessfulEndpointVariant(phata: String, plugName: String, variant: Option[String]): Future[Option[ApiEndpointVariant]] = {
    Future {
      blocking {
        db.withConnection { implicit connection =>
          SQL(
            """
              | SELECT * FROM dataplug_endpoint
              | JOIN dataplug_user ON dataplug_user.dataplug_endpoint = dataplug_endpoint.name
              |   WHERE phata = {phata}
              |     AND dataplug_endpoint = {dataplug_endpoint}
              |     AND endpoint_variant = {variant}
            """.stripMargin)
            .on(
              'phata -> phata,
              'dataplug_endpoint -> plugName,
              'variant -> variant)
            .as(apiEndpointVariantParser.singleOpt)
        }
      }
    }
  }

}
