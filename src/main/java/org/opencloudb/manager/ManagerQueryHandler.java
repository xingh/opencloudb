/*
 * Copyright 2012-2015 org.opencloudb.
 *  
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
 */
package org.opencloudb.manager;

import org.apache.log4j.Logger;
import org.opencloudb.config.ErrorCode;
import org.opencloudb.handler.ClearHandler;
import org.opencloudb.handler.ReloadHandler;
import org.opencloudb.handler.RollbackHandler;
import org.opencloudb.handler.SelectHandler;
import org.opencloudb.handler.ShowHandler;
import org.opencloudb.handler.StopHandler;
import org.opencloudb.handler.SwitchHandler;
import org.opencloudb.net.handler.FrontendQueryHandler;
import org.opencloudb.net.mysql.OkPacket;
import org.opencloudb.parser.ManagerParse;
import org.opencloudb.response.KillConnection;
import org.opencloudb.response.Offline;
import org.opencloudb.response.Online;

/**
 * @author mycat
 */
public class ManagerQueryHandler implements FrontendQueryHandler {
    private static final Logger LOGGER = Logger.getLogger(ManagerQueryHandler.class);

    private final ManagerConnection source;

    public ManagerQueryHandler(ManagerConnection source) {
        this.source = source;
    }

    @Override
    public void query(String sql) {
        ManagerConnection c = this.source;
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(new StringBuilder().append(c).append(sql).toString());
        }
        int rs = ManagerParse.parse(sql);
        switch (rs & 0xff) {
        case ManagerParse.SELECT:
            SelectHandler.handle(sql, c, rs >>> 8);
            break;
        case ManagerParse.SET:
            c.write(c.writeToBuffer(OkPacket.OK, c.allocate()));
            break;
        case ManagerParse.SHOW:
            ShowHandler.handle(sql, c, rs >>> 8);
            break;
        case ManagerParse.SWITCH:
            SwitchHandler.handler(sql, c, rs >>> 8);
            break;
        case ManagerParse.KILL_CONN:
            KillConnection.response(sql, rs >>> 8, c);
            break;
        case ManagerParse.OFFLINE:
            Offline.execute(sql, c);
            break;
        case ManagerParse.ONLINE:
            Online.execute(sql, c);
            break;
        case ManagerParse.STOP:
            StopHandler.handle(sql, c, rs >>> 8);
            break;
        case ManagerParse.RELOAD:
            ReloadHandler.handle(sql, c, rs >>> 8);
            break;
        case ManagerParse.ROLLBACK:
            RollbackHandler.handle(sql, c, rs >>> 8);
            break;
        case ManagerParse.CLEAR:
            ClearHandler.handle(sql, c, rs >>> 8);
            break;
        default:
            c.writeErrMessage(ErrorCode.ER_YES, "Unsupported statement");
        }
    }

}