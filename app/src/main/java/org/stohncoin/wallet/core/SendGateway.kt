package org.stohncoin.wallet.core

import org.stohncoin.wallet.wallet.WalletRpc

/**
 * The two node operations the send screen performs, and nothing else.
 *
 * [NodeController] is the production implementation. Keeping the screen behind this seam is what lets
 * its UI be driven by a scripted gateway: no node, no RPC socket, no wallet, but the same composable
 * the user presses.
 */
interface SendGateway {
    suspend fun estimateFee(
        address: String,
        amountSatoshis: Long,
        subtractFeeFromAmount: Boolean,
        confTarget: Int? = null
    ): WalletRpc.FeeEstimate

    suspend fun send(
        address: String,
        amountSatoshis: Long,
        passphrase: CharArray?,
        subtractFeeFromAmount: Boolean,
        confTarget: Int? = null
    ): String
}
