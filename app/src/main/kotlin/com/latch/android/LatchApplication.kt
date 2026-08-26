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
     * Read once per process. The store moves itself to `Dispatchers.IO`, so where this is
     * launched from carries no obligation.
     */
    val configuredAccounts: StateFlow<List<AccountDefaults>?> = _configuredAccounts.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        // TODO(FR-908): this is where the stored destination first comes back into the app,
        //  and so where re-validation belongs once CalendarApi is real. FR-908 requires the
        //  calendar list to be refreshed on launch, and a `destinationCalendarId` that has
        //  gone missing or lost write access to fall back to the primary calendar with the
        //  user told. Nothing reads the id yet, which is the only reason this is a comment
        //  rather than a defect: StubCalendarApi mints a fresh latch-N id every process, so
        //  a stored id already fails to name anything the next launch can see.
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
