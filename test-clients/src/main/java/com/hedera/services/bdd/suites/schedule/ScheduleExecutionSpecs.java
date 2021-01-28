package com.hedera.services.bdd.suites.schedule;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.queries.meta.HapiGetTxnRecord;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;

import java.util.List;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleSign;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class ScheduleExecutionSpecs extends HapiApiSuite {
    private static final Logger log = LogManager.getLogger(ScheduleExecutionSpecs.class);

    public static void main(String... args) {
        new ScheduleExecutionSpecs().runSuiteSync();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    protected List<HapiApiSpec> getSpecsInSuite() {
        return List.of(new HapiApiSpec[] {
                executionWithDefaultPayerWorks(),
                executionWithCustomPayerWorks(),
                executionWithDefaultPayerButNoFundsFails()
        });
    }

    public HapiApiSpec executionWithDefaultPayerWorks() {
        long transferAmount = 1;
        return defaultHapiSpec("ExecutionWithDefaultPayerWorks")
                .given(
                        cryptoCreate("sender"),
                        cryptoCreate("receiver"),
                        scheduleCreate(
                                "basicXfer",
                                cryptoTransfer(
                                        tinyBarsFromTo("sender", "receiver", transferAmount)
                                )
                        ).via("createTx")
                ).when(
                        scheduleSign("basicXfer").withSignatories("sender").via("signTx").hasKnownStatus(SUCCESS)
                ).then(
                        withOpContext((spec, opLog) -> {
                            var signTx = getTxnRecord("signTx");
                            var createTx = getTxnRecord("createTx");
                            var triggeredTx = createTx.scheduled();

                            allRunFor(spec, signTx, triggeredTx);
                            Assert.assertEquals("Wrong consensus timestamp!",
                                    signTx.getResponseRecord().getConsensusTimestamp().getNanos() + 1,
                                    triggeredTx.getResponseRecord().getConsensusTimestamp().getNanos());

                            Assert.assertEquals("Wrong transaction valid start!",
                                    createTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                                    triggeredTx.getResponseRecord().getTransactionID().getTransactionValidStart());

                            Assert.assertEquals("Wrong record account ID!",
                                    createTx.getResponseRecord().getTransactionID().getAccountID(),
                                    triggeredTx.getResponseRecord().getTransactionID().getAccountID());

                            Assert.assertTrue("Transaction not scheduled!",triggeredTx.getResponseRecord().getTransactionID().getScheduled());

                            Assert.assertEquals("Wrong triggered transaction nonce!",
                                    ByteString.EMPTY,
                                    triggeredTx.getResponseRecord().getTransactionID().getNonce());

                            Assert.assertEquals("Wrong schedule ID!",
                                    createTx.getResponseRecord().getScheduleRef(),
                                    triggeredTx.getResponseRecord().getScheduleRef());

                            Assert.assertTrue("Wrong transfer list!", transferListCheck(triggeredTx, createTx.getResponseRecord().getTransactionID().getAccountID()));
                        })
                );
    }

    public HapiApiSpec executionWithDefaultPayerButNoFundsFails() {
        long balance = 10000000000L;
        long transferAmount = 1;
        long fee = 1000000000;
        return defaultHapiSpec("ExecutionWithDefaultPayerButNoFundsFails")
                .given(
                        cryptoCreate("payingAccount").balance(balance),
                        cryptoCreate("luckyReceiver"),
                        cryptoCreate("sender"),
                        cryptoCreate("receiver"),
                        scheduleCreate(
                                "basicXfer",
                                cryptoTransfer(
                                        tinyBarsFromTo("sender", "receiver", transferAmount)
                                )
                        ).fee(fee).payingWith("payingAccount").via("createTx"),
                        getScheduleInfo("basicXfer").logged(),
                        cryptoTransfer(tinyBarsFromTo("payingAccount", "luckyReceiver", balance - fee))
                ).when(
//                        scheduleSign("basicXfer").withSignatories("sender").via("signTx").hasKnownStatus(SUCCESS)
                ).then(
//                        withOpContext((spec, opLog) -> {
//                            var signTx = getTxnRecord("signTx");
//                            var createTx = getTxnRecord("createTx");
//                            var triggeredTx = createTx.scheduled();
//
//                            allRunFor(spec, signTx, triggeredTx);
//                            Assert.assertEquals("Wrong consensus timestamp!",
//                                    signTx.getResponseRecord().getConsensusTimestamp().getNanos() + 1,
//                                    triggeredTx.getResponseRecord().getConsensusTimestamp().getNanos());
//
//                            Assert.assertEquals("Wrong transaction valid start!",
//                                    createTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
//                                    triggeredTx.getResponseRecord().getTransactionID().getTransactionValidStart());
//
//                            Assert.assertEquals("Wrong record account ID!",
//                                    createTx.getResponseRecord().getTransactionID().getAccountID(),
//                                    triggeredTx.getResponseRecord().getTransactionID().getAccountID());
//
//                            Assert.assertTrue("Transaction not scheduled!",triggeredTx.getResponseRecord().getTransactionID().getScheduled());
//
//                            Assert.assertEquals("Wrong triggered transaction nonce!",
//                                    ByteString.EMPTY,
//                                    triggeredTx.getResponseRecord().getTransactionID().getNonce());
//
//                            Assert.assertEquals("Wrong schedule ID!",
//                                    createTx.getResponseRecord().getScheduleRef(),
//                                    triggeredTx.getResponseRecord().getScheduleRef());
//
//                            Assert.assertTrue("Wrong transfer list!", transferListCheck(triggeredTx, createTx.getResponseRecord().getTransactionID().getAccountID()));
//                        })
                );
    }

    public HapiApiSpec executionWithCustomPayerWorks() {
        long transferAmount = 1;
        return defaultHapiSpec("ExecutionWithCustomPayerWorks")
                .given(
                        cryptoCreate("payingAccount"),
                        cryptoCreate("sender"),
                        cryptoCreate("receiver"),
                        scheduleCreate(
                                "basicXfer",
                                cryptoTransfer(
                                        tinyBarsFromTo("sender", "receiver", transferAmount)
                                )
                        ).payer("payingAccount").via("createTx")
                ).when(
                        scheduleSign("basicXfer").withSignatories("sender").via("signTx").hasKnownStatus(SUCCESS)
                ).then(
                        withOpContext((spec, opLog) -> {
                            var signTx = getTxnRecord("signTx");
                            var createTx = getTxnRecord("createTx");
                            var triggeredTx = createTx.scheduled();

                            allRunFor(spec, signTx, triggeredTx);
                            Assert.assertEquals("Wrong consensus timestamp!",
                                    signTx.getResponseRecord().getConsensusTimestamp().getNanos() + 1,
                                    triggeredTx.getResponseRecord().getConsensusTimestamp().getNanos());

                            Assert.assertEquals("Wrong transaction valid start!",
                                    createTx.getResponseRecord().getTransactionID().getTransactionValidStart(),
                                    triggeredTx.getResponseRecord().getTransactionID().getTransactionValidStart());

                            Assert.assertEquals("Wrong record account ID!",
                                    createTx.getResponseRecord().getTransactionID().getAccountID(),
                                    triggeredTx.getResponseRecord().getTransactionID().getAccountID());

                            Assert.assertTrue("Transaction not scheduled!",triggeredTx.getResponseRecord().getTransactionID().getScheduled());

                            Assert.assertEquals("Wrong triggered transaction nonce!",
                                    ByteString.EMPTY,
                                    triggeredTx.getResponseRecord().getTransactionID().getNonce());

                            Assert.assertEquals("Wrong schedule ID!",
                                    createTx.getResponseRecord().getScheduleRef(),
                                    triggeredTx.getResponseRecord().getScheduleRef());

                            Assert.assertTrue("Wrong transfer list!", transferListCheck(triggeredTx, asId("payingAccount", spec)));
                        })
                );
    }

    private boolean transferListCheck(HapiGetTxnRecord triggered, AccountID accountID) {
        for (AccountAmount accountAmount : triggered.getResponseRecord().getTransferList().getAccountAmountsList()) {
            if (accountAmount.getAccountID().equals(accountID)) {
                return true;
            };
        }
        return false;
    }
}
