package com.latch.android

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The app starts.
 *
 * That is the whole of it, and it is deliberately the whole of it. This is a canary, not a
 * UI test layer: it exists because the app once could not launch at all while the build was
 * green and every unit test passed. `LatchApplication` built a `Context`-dependent field in
 * a property initializer, which runs before `attachBaseContext`, and the process died before
 * its first frame.
 *
 * No JVM test could have caught that, and none ever will. They do not instantiate
 * `Application`, do not call `attachBaseContext` and do not resolve a `Context` — which is
 * exactly why the parser corpus and the FR-105 reducer tests run in milliseconds without a
 * device. The blind spot is Android's initialisation order, and only a device can see it.
 *
 * **Resist growing this file.** Assertions about what is on screen belong in the reducer
 * tests, where they cost nothing and cannot flake; `SetupStateTest` already covers AC-15 and
 * AC-16 that way. The value here is entirely in getting off the ground, so the test asserts
 * the least it can while still proving that.
 *
 * Note this does not run under `./gradlew build` — instrumented tests need a device, so it is
 * `connectedDebugAndroidTest`. A canary nobody releases the cage of does not sing.
 */
@RunWith(AndroidJUnit4::class)
class LaunchCanaryTest {

    @Test
    fun mainActivityReachesResumed() {
        // Launching instantiates LatchApplication, which is the part that was broken. If any
        // Application-scoped construction throws, the scenario never resumes and this fails.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
