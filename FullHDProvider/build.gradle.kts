plugins {
id("com.android.library")
id("kotlin-android")
}cloudstream {
setPlugin(
name = "FullHDFilm",
version = 1,
description = "FullHDFilmIzlesene CloudStream Eklentisi",
authors = listOf("q4ake"),
status = 1,
tvTypes = listOf("Movie"),
requiresResources = false,
language = "tr",
iconUrl = "🔗 https://www.fullhdfilmizlesene.now/favicon.ico"
)
}