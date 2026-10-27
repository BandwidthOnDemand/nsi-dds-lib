/**
 * TLS configuration and process-wide SSL context initialization.
 * Store configuration is mutable and contains unredacted passwords. Production
 * clients verify hostnames; nonproduction mode permits hostname mismatches while
 * retaining certificate trust checks. Loading the singleton changes the JVM's
 * default SSL context and requires coordination with other global-state users.
 */
package net.es.nsi.dds.lib.dao;
