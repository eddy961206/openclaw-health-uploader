package com.openclaw.healthuploader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

  @Test
  fun launch_main_screen_renders_core_views() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val intent = Intent(context, MainActivity::class.java).apply {
      putExtra(MainActivity.EXTRA_SKIP_AUTO_FLOW, true)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    ActivityScenario.launch<MainActivity>(intent).use {
      onView(withId(R.id.topAppBar)).check(matches(isDisplayed()))
      onView(withId(R.id.bottomNav)).check(matches(isDisplayed()))
      onView(withId(R.id.tvHeroTotal)).check(matches(isDisplayed()))
      onView(withId(R.id.rvRecent)).check(matches(isDisplayed()))
    }
  }
}
