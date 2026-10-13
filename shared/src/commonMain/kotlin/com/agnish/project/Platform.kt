package com.agnish.project

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform