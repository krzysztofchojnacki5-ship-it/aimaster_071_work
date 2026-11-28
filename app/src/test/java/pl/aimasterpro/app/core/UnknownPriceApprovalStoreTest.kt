package pl.aimasterpro.app.core

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UnknownPriceApprovalStoreTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SharedPreferencesWriteHealth.resetForTests("unknown_price_approvals_v1")
        context.getSharedPreferences("unknown_price_approvals_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun consentIsPersistedForExactProviderEndpointAndModelOnly() {
        assertTrue(UnknownPriceApprovalStore.setApproved(
            context, "Provider-A", "https://api.example/v1/", "vendor/model-a", true
        ))
        assertTrue(UnknownPriceApprovalStore.isApproved(
            context, "provider-a", "https://API.EXAMPLE/v1", "vendor/model-a"
        ))
        assertFalse(UnknownPriceApprovalStore.isApproved(
            context, "provider-a", "https://api.example/v1", "vendor/model-b"
        ))
        assertFalse(UnknownPriceApprovalStore.isApproved(
            context, "provider-b", "https://api.example/v1", "vendor/model-a"
        ))
        assertFalse(UnknownPriceApprovalStore.isApproved(
            context, "provider-a", "https://other.example/v1", "vendor/model-a"
        ))
    }

    @Test fun consentCanBeRevokedWithoutAffectingSiblingModels() {
        assertTrue(UnknownPriceApprovalStore.setApproved(
            context, "provider", "https://api.example/v1", "model-a", true
        ))
        assertTrue(UnknownPriceApprovalStore.setApproved(
            context, "provider", "https://api.example/v1", "model-b", true
        ))
        assertTrue(UnknownPriceApprovalStore.setApproved(
            context, "provider", "https://api.example/v1", "model-a", false
        ))
        assertFalse(UnknownPriceApprovalStore.isApproved(
            context, "provider", "https://api.example/v1", "model-a"
        ))
        assertTrue(UnknownPriceApprovalStore.isApproved(
            context, "provider", "https://api.example/v1", "model-b"
        ))
    }

    @Test fun corruptConsentLedgerFailsClosedAndCannotBeOverwritten() {
        val prefs = context.getSharedPreferences("unknown_price_approvals_v1", Context.MODE_PRIVATE)
        val corrupt = """[{"providerId":"provider","baseUrl":7,"modelName":"model"}]"""
        assertTrue(prefs.edit().putString("approvals", corrupt).commit())
        assertFalse(UnknownPriceApprovalStore.isApproved(
            context, "provider", "https://api.example/v1", "model"
        ))
        assertFalse(UnknownPriceApprovalStore.setApproved(
            context, "provider", "https://api.example/v1", "model", true
        ))
        assertTrue(prefs.getString("approvals", null) == corrupt)
    }

    @Test fun invalidIdentityCannotBeApproved() {
        assertFalse(UnknownPriceApprovalStore.setApproved(context, "", "https://api.example/v1", "model", true))
        assertFalse(UnknownPriceApprovalStore.setApproved(context, "provider", "", "model", true))
        assertFalse(UnknownPriceApprovalStore.setApproved(context, "provider", "https://api.example/v1", "", true))
    }
}
