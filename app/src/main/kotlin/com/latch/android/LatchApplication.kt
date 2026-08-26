package com.latch.android

import android.app.Application
import com.latch.android.setup.SetupCoordinator
import com.latch.android.setup.StubAuthClient
import com.latch.android.setup.StubCalendarApi
import com.latch.android.setup.StubTasksApi
import com.latch.data.AccountDefaults
import com.latch.data.EncryptedAccountDefaultsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * NFR-104: no persistent background service other than the optional notification listener.
 * Nothing is started here, and nothing should be — capture layers are entry points that the
 * system brings up on demand.
 */
class LatchApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob())

    val accountDefaults = EncryptedAccountDefaultsStore(this)

    private val _configuredAccounts = MutableStateFlow<List<AccountDefaults>?>(null)

    /**
     * FR-101: what decides whether setup runs. Null while the read is in flight, which is
     * a state the UI has to have — an empty list and "not read yet" mean opposite things,
     * and treating them alike would show setup to a user who has already finished it.
     *
     * Read once per process, off the main thread. The store's suspend functions block on
     * their caller's thread (`:data` has no coroutines dependency), and this scope is not
     * the main one.
     */
    val configuredAccounts: StateFlow<List<AccountDefaults>?> = _configuredAccounts.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        appScope.launch { _configuredAccounts.value = accountDefaults.allAccounts() }
    }

    /**
     * FR-105: setup state is held here, for the life of the process and nowhere else, so
     * that abandoning setup — or losing the process partway through it — leaves nothing in
     * the user's Google account and nothing on disk (AC-16).
     *
     * The coordinator outlives MainActivity on purpose. A rotation partway through setup
     * must not restart it, and this is the way to get that without a retained ViewModel,
     * which would mean a dependency the app does not carry yet (NFR-501).
     */
    val setupCoordinator: SetupCoordinator by lazy {
        SetupCoordinator(
            auth = StubAuthClient(),
            calendarApi = StubCalendarApi(),
            tasksApi = StubTasksApi(),
            defaultsStore = accountDefaults,
            scope = appScope,
            latchCalendarSummary = getString(R.string.latch_calendar_summary),
            latchCalendarDescription = getString(R.string.latch_calendar_description),
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}
