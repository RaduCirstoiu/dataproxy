package com.dataproxy.proxy

import java.net.InetAddress

/** Keep resolver order, remove duplicates, and bound the total retry count. */
internal fun selectConnectCandidates(
    addresses: List<InetAddress>,
    limit: Int,
): List<InetAddress> = addresses.distinct().take(limit)
