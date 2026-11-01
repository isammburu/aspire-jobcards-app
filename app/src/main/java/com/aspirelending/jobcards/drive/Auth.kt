package com.aspirelending.jobcards.drive

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task

object Auth {
    private val req = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive")))
        .build()

    fun authorize(c: Context): Task<AuthorizationResult> {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c)
        if (availability != ConnectionResult.SUCCESS) {
            throw IllegalStateException("Google Play services unavailable. Drive sync requires Google Play services.")
        }
        return Identity.getAuthorizationClient(c).authorize(req)
    }
}
