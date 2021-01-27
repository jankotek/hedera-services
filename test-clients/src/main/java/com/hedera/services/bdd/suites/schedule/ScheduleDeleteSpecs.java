package com.hedera.services.bdd.suites.schedule;

/*-
 * ‌
 * Hedera Services Test Clients
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
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

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getScheduleInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.scheduleDelete;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_SCHEDULE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SCHEDULE_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNAUTHORIZED;

public class ScheduleDeleteSpecs extends HapiApiSuite {
    private static final Logger log = LogManager.getLogger(ScheduleDeleteSpecs.class);

    public static void main(String... args) {
        new ScheduleDeleteSpecs().runSuiteSync();
    }

    @Override
    protected Logger getResultsLogger() {
        return log;
    }

    @Override
    protected List<HapiApiSpec> getSpecsInSuite() {
        return List.of(new HapiApiSpec[] {
                    followsHappyPath(),
                    deleteWithNoAdminKeyFails(),
                    unauthorizedDeletionFails(),
                    deletingADeletedTxnFails(),
                    deletingNonExistingFails()
                }
        );
    }

    private HapiApiSpec followsHappyPath() {
        return defaultHapiSpec("FollowsHappyPath")
                .given(
                        newKeyNamed("admin"),
                        scheduleCreate("validScheduledTxn", cryptoCreate("secondary"))
                                .adminKey("admin")
                )
                .when(
                        scheduleDelete("validScheduledTxn")
                                .signedBy("admin", DEFAULT_PAYER)
                                .hasKnownStatus(SUCCESS)
                )
                .then(
                        getScheduleInfo("validScheduledTxn")
                                .logged().hasAnswerOnlyPrecheck(SCHEDULE_WAS_DELETED)
                );
    }

    private HapiApiSpec deleteWithNoAdminKeyFails() {
        return defaultHapiSpec("DeleteWithNoAdminKeyFails")
                .given(
                        scheduleCreate("validScheduledTxn", cryptoCreate("secondary"))
                )
                .when(
                        scheduleDelete("validScheduledTxn")
                                .hasKnownStatus(SCHEDULE_IS_IMMUTABLE)
                )
                .then(
                );
    }

    private HapiApiSpec unauthorizedDeletionFails() {
        return defaultHapiSpec("UnauthorizedDeletionFails")
                .given(
                        newKeyNamed("admin"),
                        scheduleCreate("validScheduledTxn", cryptoCreate("secondary"))
                                .adminKey("admin")
                )
                .when(
                        scheduleDelete("validScheduledTxn")
                                .signedBy(DEFAULT_PAYER)
                                .hasKnownStatus(UNAUTHORIZED)
                )
                .then(
                );
    }

    private HapiApiSpec deletingADeletedTxnFails() {
        return defaultHapiSpec("DeletingADeletedTxnFails")
                .given(
                        newKeyNamed("admin"),
                        scheduleCreate("validScheduledTxn", cryptoCreate("secondary"))
                                .adminKey("admin"),
                        scheduleDelete("validScheduledTxn")
                                .signedBy("admin", DEFAULT_PAYER))
                .when(
                        scheduleDelete("validScheduledTxn")
                                .signedBy("admin", DEFAULT_PAYER)
                                .hasKnownStatus(SCHEDULE_WAS_DELETED)
                )
                .then(
                );
    }

    private HapiApiSpec deletingNonExistingFails() {
        return defaultHapiSpec("DeletingNonExistingFails")
                .given()
                .when(
                        scheduleDelete("0.0.534")
                                .hasKnownStatus(INVALID_SCHEDULE_ID)
                )
                .then(
                );
    }
}
