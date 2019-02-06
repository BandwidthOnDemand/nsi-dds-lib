/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package net.es.nsi.dds.lib.client;

import javax.ws.rs.core.Response.Status;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 *
 * @author hacksaw
 */
@Data
@EqualsAndHashCode
public class Result {
  Status status = Status.BAD_REQUEST;
  long lastModified;
}
