/**
 * Construction and decoding of DDS document envelopes.
 * XML content and optional detached signatures use gzip content encoding followed
 * by Base64 transfer encoding. Signature validation is optional and does not
 * establish certificate trust or authorization. Builders retain mutable input
 * references and are intended for externally coordinated use.
 */
package net.es.nsi.dds.lib.util;
