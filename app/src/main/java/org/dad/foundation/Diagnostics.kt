package org.dad.foundation

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import okhttp3.Interceptor
import okhttp3.Response
import retrofit2.Invocation
import java.io.IOException

class DadApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.initialize(this)
    }
}

/** A separate opt-in: usage analytics consent covers first-party data only. */
class DiagnosticsConsent(context: Context) {
    private val prefs = context.getSharedPreferences("org.dad.diagnostics", Context.MODE_PRIVATE)
    var allowed: Boolean
        get() = prefs.getBoolean("allowed", false)
        set(value) = prefs.edit { putBoolean("allowed", value) }
}

internal object Diagnostics : ErrorReporter {
    @Volatile private var sdk: FirebaseCrashlytics? = null
    val available: Boolean get() = sdk != null
    @Volatile private var consent: DiagnosticsConsent? = null
    private val reporter = DedupingErrorReporter(object : ErrorReporter {
        override fun report(error: ClientError) {
            // Never attach a response, URL, credential, original exception message or user identifier.
            sdk?.recordException(IllegalStateException("DAD.${error.screen}.${error.operation}.${error.kind.name}"))
        }
    })
    private val consented = ConsentErrorReporter({ consent?.allowed == true }, reporter)

    fun initialize(context: Context) {
        consent = DiagnosticsConsent(context)
        // Synthetic/debug journeys never send diagnostics to a real provider.
        if (!BuildConfig.DEBUG && FirebaseApp.getApps(context).isNotEmpty()) {
            try {
                sdk = FirebaseCrashlytics.getInstance()
                sdk?.setCrashlyticsCollectionEnabled(consent?.allowed == true)
                if (consent?.allowed != true) sdk?.deleteUnsentReports()
            } catch (_: IllegalStateException) {
                // A missing diagnostics component must not prevent signing in or giving.
                sdk = null
            }
        }
    }

    fun choose(allowed: Boolean) {
        consent?.allowed = allowed
        sdk?.setCrashlyticsCollectionEnabled(allowed)
        if (!allowed) sdk?.deleteUnsentReports()
        reporter.clear()
    }

    override fun report(error: ClientError) {
        consented.report(error)
    }
}

/** Reports transport/server failures without reading bodies or recording identifying paths. */
internal class ErrorObserver(private val reporter: ErrorReporter = Diagnostics) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val operation = chain.request().tag(Invocation::class.java)?.method()?.name ?: "request"
        val response = try { chain.proceed(chain.request()) }
        catch (error: IOException) {
            reporter.report(ClientError("api", operation, ClientError.Kind.UNAVAILABLE, ""))
            throw error
        }
        if (response.code >= 500) reporter.report(ClientError("api", operation, ClientError.Kind.UNAVAILABLE, ""))
        return response
    }
}
