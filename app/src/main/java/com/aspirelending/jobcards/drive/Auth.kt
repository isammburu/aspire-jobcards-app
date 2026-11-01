package com.aspirelending.jobcards.drive

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task

object Auth {
    // Full Drive scope: needed to write into a folder the app did not create (drive.file cannot).
    private val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/drive"))).build()
    fun authorize(c: Context): Task<AuthorizationResult> = Identity.getAuthorizationClient(c).authorize(req)
}
