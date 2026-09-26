package com.easycoderemote.ui.navigation

import android.net.Uri

/** Navigation route constants and extras used by deep links. */
object Routes {
    const val ROUTE_PROFILES = "profiles"
    const val ROUTE_PROFILE_SETUP = "profileSetup"
    const val ROUTE_SESSIONS = "sessions"
    const val ROUTE_SESSION_DETAIL = "sessionDetail/{sessionId}"
    const val ROUTE_CONFIG = "config"
    const val ROUTE_SETTINGS = "settings"
    const val ROUTE_CERT_CHANGED = "certChanged"
    const val ROUTE_APPROVAL = "approval/{sessionId}"
    const val ROUTE_PROVIDER_MODELS = "config/provider/{providerID}"

    const val ARG_SESSION_ID = "sessionId"
    const val ARG_PROVIDER_ID = "providerID"

    fun sessionDetail(sessionId: String) = "sessionDetail/$sessionId"
    fun approval(sessionId: String) = "approval/$sessionId"

    /** Provider ids may contain `/` (e.g. "openrouter/deepseek-..."), so the id
     *  is always URL-encoded when embedded in the route. */
    fun providerModels(providerID: String) = "config/provider/${Uri.encode(providerID)}"

    const val EXTRA_ROUTE = "ecr_route"
    const val EXTRA_SESSION_ID = "ecr_session_id"
}