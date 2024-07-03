/** ******************************************************************************
 * Copyright (c) 2024 Precies. Software OU and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 * ****************************************************************************** */
package org.eclipse.openvsx.repositories;

import org.eclipse.openvsx.entities.UserData;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import static org.eclipse.openvsx.jooq.Tables.PERSONAL_ACCESS_TOKEN;
import static org.eclipse.openvsx.jooq.Tables.USER_DATA;

@Component
public class PersonalAccessTokenJooqRepository {
    private final DSLContext dsl;

    public PersonalAccessTokenJooqRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public boolean hasToken(String value) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(PERSONAL_ACCESS_TOKEN)
                        .where(PERSONAL_ACCESS_TOKEN.VALUE.eq(value))
        );
    }

    public boolean isAdminToken(String value) {
        return dsl.fetchExists(
                dsl.selectOne()
                        .from(PERSONAL_ACCESS_TOKEN)
                        .join(USER_DATA).on(USER_DATA.ID.eq(PERSONAL_ACCESS_TOKEN.USER_DATA))
                        .where(PERSONAL_ACCESS_TOKEN.VALUE.eq(value))
                        .and(PERSONAL_ACCESS_TOKEN.ACTIVE.eq(true))
                        .and(USER_DATA.ROLE.eq(UserData.ROLE_ADMIN))
        );
    }
}
