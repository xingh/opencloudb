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
package org.opencloudb;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;
import org.apache.log4j.helpers.LogLog;
import org.opencloudb.config.model.SystemConfig;
import org.opencloudb.manager.ManagerConnectionFactory;
import org.opencloudb.mysql.MySQLDataNode;
import org.opencloudb.net.NIOAcceptor;
import org.opencloudb.net.NIOConnector;
import org.opencloudb.net.NIOProcessor;
import org.opencloudb.paser.recognizer.mysql.lexer.MySQLLexer;
import org.opencloudb.server.ServerConnectionFactory;
import org.opencloudb.statistic.SQLRecorder;
import org.opencloudb.util.ExecutorUtil;
import org.opencloudb.util.NameableExecutor;
import org.opencloudb.util.TimeUtil;

/**
 * @author mycat
 */
public class MycatServer {
    public static final String NAME = "MyCat";
    private static final long LOG_WATCH_DELAY = 60000L;
    private static final long TIME_UPDATE_PERIOD = 20L;
    private static final MycatServer INSTANCE = new MycatServer();
    private static final Logger LOGGER = Logger.getLogger(MycatServer.class);

    public static final MycatServer getInstance() {
        return INSTANCE;
    }

    private final MycatConfig config;
    private final Timer timer;
    private final NameableExecutor managerExecutor;
    private final NameableExecutor timerExecutor;
    private final NameableExecutor initExecutor;
    private final SQLRecorder sqlRecorder;
    private final AtomicBoolean isOnline;
    private final long startupTime;
    private NIOProcessor[] processors;
    private NIOConnector connector;
    private NIOAcceptor manager;
    private NIOAcceptor server;

    private MycatServer() {
        this.config = new MycatConfig();
        SystemConfig system = config.getSystem();
        MySQLLexer.setCStyleCommentVersion(system.getParserCommentVersion());
        this.timer = new Timer(NAME + "Timer", true);
        this.initExecutor = ExecutorUtil.create("InitExecutor", system.getInitExecutor());
        this.timerExecutor = ExecutorUtil.create("TimerExecutor", system.getTimerExecutor());
        this.managerExecutor = ExecutorUtil.create("ManagerExecutor", system.getManagerExecutor());
        this.sqlRecorder = new SQLRecorder(system.getSqlRecordCount());
        this.isOnline = new AtomicBoolean(true);
        this.startupTime = TimeUtil.currentTimeMillis();
    }

    public MycatConfig getConfig() {
        return config;
    }

    public void beforeStart(String dateFormat) {
        String home = System.getProperty("mycat.home");
        if (home == null) {
            SimpleDateFormat sdf = new SimpleDateFormat(dateFormat);
            LogLog.warn(sdf.format(new Date()) + " [mycat.home] is not set.");
        } else {
            Log4jInitializer.configureAndWatch(home + "/conf/log4j.xml", LOG_WATCH_DELAY);
        }
    }

    public void startup() throws IOException {
        // server startup
        LOGGER.info("===============================================");
        LOGGER.info(NAME + " is ready to startup ...");
        SystemConfig system = config.getSystem();
        timer.schedule(updateTime(), 0L, TIME_UPDATE_PERIOD);

        // startup processors
        LOGGER.info("Startup processors ...");
        int handler = system.getProcessorHandler();
        int executor = system.getProcessorExecutor();
        processors = new NIOProcessor[system.getProcessors()];
        for (int i = 0; i < processors.length; i++) {
            processors[i] = new NIOProcessor("Processor" + i, handler, executor);
            processors[i].startup();
        }
        timer.schedule(processorCheck(), 0L, system.getProcessorCheckPeriod());

        // startup connector
        LOGGER.info("Startup connector ...");
        connector = new NIOConnector(NAME + "Connector");
        connector.setProcessors(processors);
        connector.start();

        // init dataNodes
        Map<String, MySQLDataNode> dataNodes = config.getDataNodes();
        LOGGER.info("Initialize dataNodes ...");
        for (MySQLDataNode node : dataNodes.values()) {
            node.init(1, 0);
        }
        timer.schedule(dataNodeIdleCheck(), 0L, system.getDataNodeIdleCheckPeriod());
        timer.schedule(dataNodeHeartbeat(), 0L, system.getDataNodeHeartbeatPeriod());

        // startup manager
        ManagerConnectionFactory mf = new ManagerConnectionFactory();
        mf.setCharset(system.getCharset());
        mf.setIdleTimeout(system.getIdleTimeout());
        manager = new NIOAcceptor(NAME + "Manager", system.getManagerPort(), mf);
        manager.setProcessors(processors);
        manager.start();
        LOGGER.info(manager.getName() + " is started and listening on " + manager.getPort());

        // startup server
        ServerConnectionFactory sf = new ServerConnectionFactory();
        sf.setCharset(system.getCharset());
        sf.setIdleTimeout(system.getIdleTimeout());
        server = new NIOAcceptor(NAME + "Server", system.getServerPort(), sf);
        server.setProcessors(processors);
        server.start();
        timer.schedule(clusterHeartbeat(), 0L, system.getClusterHeartbeatPeriod());

        // server started
        LOGGER.info(server.getName() + " is started and listening on " + server.getPort());
        LOGGER.info("===============================================");
    }

    public NIOProcessor[] getProcessors() {
        return processors;
    }

    public NIOConnector getConnector() {
        return connector;
    }

    public NameableExecutor getManagerExecutor() {
        return managerExecutor;
    }

    public NameableExecutor getTimerExecutor() {
        return timerExecutor;
    }

    public NameableExecutor getInitExecutor() {
        return initExecutor;
    }

    public SQLRecorder getSqlRecorder() {
        return sqlRecorder;
    }

    public long getStartupTime() {
        return startupTime;
    }

    public boolean isOnline() {
        return isOnline.get();
    }

    public void offline() {
        isOnline.set(false);
    }

    public void online() {
        isOnline.set(true);
    }

    // 系统时间定时更新任务
    private TimerTask updateTime() {
        return new TimerTask() {
            @Override
            public void run() {
                TimeUtil.update();
            }
        };
    }

    // 处理器定时检查任务
    private TimerTask processorCheck() {
        return new TimerTask() {
            @Override
            public void run() {
                timerExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        for (NIOProcessor p : processors) {
                            p.check();
                        }
                    }
                });
            }
        };
    }

    // 数据节点定时连接空闲超时检查任务
    private TimerTask dataNodeIdleCheck() {
        return new TimerTask() {
            @Override
            public void run() {
                timerExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        Map<String, MySQLDataNode> nodes = config.getDataNodes();
                        for (MySQLDataNode node : nodes.values()) {
                            node.idleCheck();
                        }
                        Map<String, MySQLDataNode> _nodes = config.getBackupDataNodes();
                        if (_nodes != null) {
                            for (MySQLDataNode node : _nodes.values()) {
                                node.idleCheck();
                            }
                        }
                    }
                });
            }
        };
    }

    // 数据节点定时心跳任务
    private TimerTask dataNodeHeartbeat() {
        return new TimerTask() {
            @Override
            public void run() {
                timerExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        Map<String, MySQLDataNode> nodes = config.getDataNodes();
                        for (MySQLDataNode node : nodes.values()) {
                            node.doHeartbeat();
                        }
                    }
                });
            }
        };
    }

    // 集群节点定时心跳任务
    private TimerTask clusterHeartbeat() {
        return new TimerTask() {
            @Override
            public void run() {
                timerExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        Map<String, MycatNode> nodes = config.getCluster().getNodes();
                        for (MycatNode node : nodes.values()) {
                            node.doHeartbeat();
                        }
                    }
                });
            }
        };
    }

}