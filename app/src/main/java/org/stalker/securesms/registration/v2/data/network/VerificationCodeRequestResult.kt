/*
 * Copyright 2024 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.stalker.securesms.registration.v2.data.network

import okio.IOException
import org.signal.core.util.logging.Log
import org.stalker.securesms.registration.v2.data.RegistrationRepository
import org.whispersystems.signalservice.api.NetworkResult
import org.whispersystems.signalservice.api.push.exceptions.CaptchaRequiredException
import org.whispersystems.signalservice.api.push.exceptions.ExternalServiceFailureException
import org.whispersystems.signalservice.api.push.exceptions.ImpossiblePhoneNumberException
import org.whispersystems.signalservice.api.push.exceptions.InvalidTransportModeException
import org.whispersystems.signalservice.api.push.exceptions.MalformedRequestException
import org.whispersystems.signalservice.api.push.exceptions.NonNormalizedPhoneNumberException
import org.whispersystems.signalservice.api.push.exceptions.PushChallengeRequiredException
import org.whispersystems.signalservice.api.push.exceptions.RateLimitException
import org.whispersystems.signalservice.api.push.exceptions.RegistrationRetryException
import org.whispersystems.signalservice.api.push.exceptions.TokenNotAcceptedException
import org.whispersystems.signalservice.internal.push.RegistrationSessionMetadataJson
import org.whispersystems.signalservice.internal.push.RegistrationSessionMetadataResponse
import org.whispersystems.signalservice.internal.util.JsonUtil

/**
 * This is a processor to map a [RegistrationSessionMetadataResponse] to all the known outcomes.
 */
sealed class VerificationCodeRequestResult(cause: Throwable?) : RegistrationResult(cause) {
  companion object {

    private val TAG = Log.tag(VerificationCodeRequestResult::class.java)

    @JvmStatic
    fun from(networkResult: NetworkResult<RegistrationSessionMetadataResponse>): VerificationCodeRequestResult {
      return when (networkResult) {
        is NetworkResult.Success -> {
          val challenges = networkResult.result.body.requestedInformation
          if (challenges.isNotEmpty()) {
            ChallengeRequired(challenges)
          } else {
            Success(
              sessionId = networkResult.result.body.id,
              allowedToRequestCode = networkResult.result.body.allowedToRequestCode,
              nextSmsTimestamp = RegistrationRepository.deriveTimestamp(networkResult.result.headers, networkResult.result.body.nextSms),
              nextCallTimestamp = RegistrationRepository.deriveTimestamp(networkResult.result.headers, networkResult.result.body.nextCall)
            )
          }
        }

        is NetworkResult.ApplicationError -> UnknownError(networkResult.throwable)
        is NetworkResult.NetworkError -> UnknownError(networkResult.exception)
        is NetworkResult.StatusCodeError -> {
          when (val cause = networkResult.exception) {
            is PushChallengeRequiredException -> createChallengeRequiredProcessor(networkResult)
            is CaptchaRequiredException -> createChallengeRequiredProcessor(networkResult)
            is RateLimitException -> createRateLimitProcessor(cause)
            is ImpossiblePhoneNumberException -> ImpossibleNumber(cause)
            is NonNormalizedPhoneNumberException -> NonNormalizedNumber(cause)
            is TokenNotAcceptedException -> TokenNotAccepted(cause)
            is ExternalServiceFailureException -> ExternalServiceFailure(cause)
            is InvalidTransportModeException -> InvalidTransportModeFailure(cause)
            is MalformedRequestException -> MalformedRequest(cause)
            is RegistrationRetryException -> MustRetry(cause)
            else -> UnknownError(cause)
          }
        }
      }
    }

    private fun createChallengeRequiredProcessor(errorResult: NetworkResult.StatusCodeError<RegistrationSessionMetadataResponse>): VerificationCodeRequestResult {
      if (errorResult.body == null) {
        Log.w(TAG, "Attempted to parse error body with response code ${errorResult.code} for list of requested information, but body was null.")
        return UnknownError(errorResult.exception)
      }

      try {
        val response = JsonUtil.fromJson(errorResult.body, RegistrationSessionMetadataJson::class.java)
        return ChallengeRequired(response.requestedInformation)
      } catch (parseException: IOException) {
        Log.w(TAG, "Attempted to parse error body for list of requested information, but encountered exception.", parseException)
        return UnknownError(parseException)
      }
    }

    private fun createRateLimitProcessor(exception: RateLimitException): VerificationCodeRequestResult {
      return if (exception.retryAfterMilliseconds.isPresent) {
        RateLimited(exception, exception.retryAfterMilliseconds.get())
      } else {
        AttemptsExhausted(exception)
      }
    }
  }

  class Success(val sessionId: String, val allowedToRequestCode: Boolean, val nextSmsTimestamp: Long, val nextCallTimestamp: Long) : VerificationCodeRequestResult(null)

  class ChallengeRequired(val challenges: List<String>) : VerificationCodeRequestResult(null)

  class RateLimited(cause: Throwable, val timeRemaining: Long) : VerificationCodeRequestResult(cause)

  class AttemptsExhausted(cause: Throwable) : VerificationCodeRequestResult(cause)

  class ImpossibleNumber(cause: Throwable) : VerificationCodeRequestResult(cause)

  class NonNormalizedNumber(cause: Throwable) : VerificationCodeRequestResult(cause)

  class TokenNotAccepted(cause: Throwable) : VerificationCodeRequestResult(cause)

  class ExternalServiceFailure(cause: Throwable) : VerificationCodeRequestResult(cause)

  class InvalidTransportModeFailure(cause: Throwable) : VerificationCodeRequestResult(cause)

  class MalformedRequest(cause: Throwable) : VerificationCodeRequestResult(cause)

  class MustRetry(cause: Throwable) : VerificationCodeRequestResult(cause)

  class UnknownError(cause: Throwable) : VerificationCodeRequestResult(cause)
}
