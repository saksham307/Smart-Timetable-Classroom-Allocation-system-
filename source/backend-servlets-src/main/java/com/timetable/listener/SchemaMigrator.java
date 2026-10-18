package com.timetable.listener;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;


@WebListener
public class SchemaMigrationListener implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        SchemaMigrator.run(msg -> sce.getServletContext().log(msg));
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) { }
}
