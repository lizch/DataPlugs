/*
 * Copyright (C) 2016 HAT Data Exchange Ltd - All Rights Reserved
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * Written by Andrius Aucinas <andrius.aucinas@hatdex.org>, 10 2016
 */

import sbt.Keys._
import sbt._

object Build extends Build {

  lazy val hatClientPlay = Project(
    id = "hat-client-play",
    base = file("hat-client-scala-play")
  )

  lazy val commonPlay = Project(
    id = "common-play",
    base = file("commonPlay")
  )

  lazy val marketsquareClientPlay = Project(
    id = "marketsquare-client-play",
    base = file("marketsquare-client-scala-play"),
    dependencies = Seq(hatClientPlay % "compile->compile;test->test")
  )

  lazy val dataplug = Project(
    id = "dataplug",
    base = file("dataplug"),
    dependencies = Seq(
      hatClientPlay % "compile->compile;test->test",
      marketsquareClientPlay % "compile->compile;test->test",
      commonPlay % "compile->compile;test->test")
  )

  lazy val dataplugTwitter = Project(
    id = "dataplug-twitter",
    base = file("dataplug-twitter"),
    dependencies = Seq(
      hatClientPlay % "compile->compile;test->test",
      marketsquareClientPlay % "compile->compile;test->test",
      commonPlay % "compile->compile;test->test",
      dataplug % "compile->compile;test->test"),
    aggregate = Seq(dataplug)
  )

  val root = Project(
    id = "dataplug-project",
    base = file("."),
    aggregate = Seq(
      commonPlay,
      hatClientPlay,
      marketsquareClientPlay,
      dataplug,
      dataplugTwitter
    ),
    settings = Defaults.coreDefaultSettings ++
      // APIDoc.settings ++
      Seq(
        publishLocal := {},
        publishM2 := {},
        publishArtifact := false
      )
  )
}