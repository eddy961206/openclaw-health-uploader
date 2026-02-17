package com.openclaw.healthuploader

class Event<out T>(private val content: T) {
  private var handled = false

  fun getContentIfNotHandled(): T? {
    if (handled) return null
    handled = true
    return content
  }

  fun peek(): T = content
}

