package com.openclaw.healthuploader

import org.json.JSONObject

fun JSONObject.putIfNotNull(key: String, value: Any?) {
  if (value == null) return
  put(key, value)
}

