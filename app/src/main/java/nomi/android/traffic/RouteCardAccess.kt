package nomi.android.traffic

import android.content.Context
import nomi.product.nav.FileRouteCardStore
import nomi.product.nav.RouteCardRepository
import java.io.File

/**
 * Shared recent-route store for notification and accessibility hosts.
 */
object RouteCardAccess {
    private val lock = Any()
    private var repository: RouteCardRepository? = null
    private var naverIntake: NaverRouteCardIntake? = null
    private var googleIntake: GoogleRouteCardIntake? = null

    fun repository(context: Context): RouteCardRepository {
        synchronized(lock) {
            repository?.let { return it }
            val created = RouteCardRepository(
                FileRouteCardStore(File(context.applicationContext.filesDir, STORE_FILE).toPath()),
            )
            repository = created
            return created
        }
    }

    fun naverIntake(context: Context): NaverRouteCardIntake {
        synchronized(lock) {
            naverIntake?.let { return it }
            val created = NaverRouteCardIntake(repository(context))
            naverIntake = created
            return created
        }
    }

    fun googleIntake(context: Context): GoogleRouteCardIntake {
        synchronized(lock) {
            googleIntake?.let { return it }
            val created = GoogleRouteCardIntake(repository(context))
            googleIntake = created
            return created
        }
    }

    const val STORE_FILE = "nomi-route-cards-v1.json"
}
