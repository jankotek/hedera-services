package com.hedera.services.utils;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.sigs.sourcing.PubKeyToSigBytes;
import com.hedera.services.usage.BaseTransactionMeta;
import com.hedera.services.usage.consensus.SubmitMessageMeta;
import com.hedera.services.usage.crypto.CryptoTransferMeta;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.swirlds.common.SwirldTransaction;

import java.util.Map;

/**
 * Defines a type that gives access to several commonly referenced
 * parts of a Hedera Services gRPC {@link Transaction}.
 */
public interface TxnAccessor {
    int sigMapSize();
    int numSigPairs();
    SignatureMap getSigMap();
    default PubKeyToSigBytes getPkToSigsFn() {
        throw new UnsupportedOperationException();
    }
    default void setSigMeta(RationalizedSigMeta sigMeta) {
        throw new UnsupportedOperationException();
    }
    default RationalizedSigMeta getSigMeta() {
        throw new UnsupportedOperationException();
    }

    default BaseTransactionMeta baseUsageMeta() {
        throw new UnsupportedOperationException();
    }
    default CryptoTransferMeta availXferUsageMeta() {
        throw new UnsupportedOperationException();
    }
    default SubmitMessageMeta availSubmitUsageMeta() {
        throw new UnsupportedOperationException();
    }

    long getOfferedFee();
    AccountID getPayer();
    TransactionID getTxnId();
    HederaFunctionality getFunction();

    byte[] getMemoUtf8Bytes();
    String getMemo();
    boolean memoHasZeroByte();

    byte[] getHash();
    byte[] getTxnBytes();
    byte[] getSignedTxnWrapperBytes();
    Transaction getSignedTxnWrapper();
    TransactionBody getTxn();

    boolean canTriggerTxn();
    boolean isTriggeredTxn();
    ScheduleID getScheduleRef();

    default SwirldTransaction getPlatformTxn() {
        throw new UnsupportedOperationException();
    }

    default Map<String, Object> getSpanMap() {
        throw new UnsupportedOperationException();
    }
}
