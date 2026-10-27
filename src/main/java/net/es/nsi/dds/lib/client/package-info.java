/**
 * Pooled HTTP access to DDS documents and subscriptions.
 * Client methods close their per-call responses; callers own long-lived clients
 * and responses obtained through direct Jakarta REST access. Results are mutable
 * snapshots of status, timestamps, and model references, not authorization or
 * signature-verification decisions. Consult each result type for equality semantics.
 */
package net.es.nsi.dds.lib.client;
