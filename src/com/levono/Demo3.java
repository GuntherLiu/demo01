package com.levono;



// the learning class
/*

	this the class of NodesServlet, and the method of handlePut
	
	for the action "refesh inventory", the path.length is 1
	
	
	
	
*/
public class Demo3 {
	
	
	 @Override
	    protected void handlePut (HttpServletRequest req, HttpServletResponse resp)
	    throws ServletException, IOException {
	        try {
	            // Since this is a sub-servlet of MainDMServlet, these are the
	            // only possible path[] cases:
	            // path = ['node']              (path.length = 1)
	            // path = ['node','{uuid}']     (path.length = 2)
	            // path = ['node','{uuid}',...] (path.length > 2)

	            String[] path = RestfulUtils.getPathParts (req);
	            String ctxPath = req.getContextPath ();

	            String       user     = RestApiUtils.getUserName (req);
	            String       password = RestApiUtils.getPassword (req);
	            String       addr     = req.getRemoteAddr ();
	            String       body     = RestfulUtils.getBody (req);
	            JSONResource jr       = JSONResourceFactory.getResource (body);

	            Locale locale = (req.getLocale () == null) ? Locale.getDefault () : req.getLocale ();

	            resp.setContentType (CONTENT_TYPE_UTF8);

	            // Process/interrogate query parameters, distilling them to a set of flags stored
	            // in an object we can then pass around as needed.
	            QueryParameterFlags paramFlags = QueryParameterFlags.processQueryParameters (req.getParameterMap ());
	            if (paramFlags == null) {
	                RestfulUtils.sendError (resp, HttpServletResponse.SC_BAD_REQUEST);
	                return;
	            }

	            if (path.length > 0) {
	                // Bypass ResourceLocked check for Mount Media Request since it is only issued from OS Deploy
	                // and it does not have embedded parent JobId in the requested JSON (added server lock for
	                // FW updates to this check)
	                if ((path.length != 2) ||
	                    (! (("/node".equals (ctxPath)) || "/nodes".equals (ctxPath))) ||
	                    ((! (path[1].equalsIgnoreCase (URI_MOUNT_MEDIA))) &&
	                     (! (path[1].equalsIgnoreCase (URI_PART_LOCK))))) {
	                    log.debug ("Checking lock");
	                    String  uuid   = path[0].toUpperCase ();
	                    boolean locked = isResourceLocked (uuid, (JSONObject) jr);
	                    if (locked) {
	                        String     explain = "MSG_RESOURCE_LOCKED";
	                        JSONObject jsonObj = MessageUtils.getJSONResponse (MessageUtils.MSG_CONFLICT,
	                            MessageUtils.getString (explain, locale), SC_CONFLICT, locale);
	                        RestfulUtils.sendResponse (SC_CONFLICT, jsonObj.toJson (), resp);
	                        return;
	                    }
	                }
	            }

	            if ((path.length == 1) && (("/node".equals (ctxPath)) || "/nodes".equals (ctxPath))) {
	                // URI is for a specific node, we will get UUID and continue
	                String uuid = path[0].toUpperCase ();
	                log.info (" NodesServlet body: {}", RestApiUtils.hideSftpPassword (body));

	                if (! (jr instanceof JSONObject)) {
	                    log.error ("Invalid format on node request: non-object PUT body");
	                    RestfulUtils.sendError (resp, HttpServletResponse.SC_BAD_REQUEST);
	                    return;
	                }

	                // Ensure the uuid is of a node we are managing
	                HardwareManager hm = CoreInventoryService.getService ().getManagerByUUID (uuid);

	                // No hardware manager found, meaning there is no major component with this UUID; return not found
	                if (hm == null) {
	                    log.error ("NodesServlet hm was not found for UUID {}", uuid);
	                    RestfulUtils.sendError (resp, HttpServletResponse.SC_NOT_FOUND);
	                    return;
	                }

	                // Hardware manager found with this UUID, but it is not for a server; bad request
	                if (! (hm instanceof ServerManager)) {
	                    log.error ("NodesServlet hm was for a ServerManager for UUID {}", uuid);
	                    RestfulUtils.sendError (resp, HttpServletResponse.SC_BAD_REQUEST);
	                    return;
	                }

	                // Proceed with the request submitted, on the hardware we have a manager for, and send the response
	                // TODO Make sure we really need the synchronization behavior of StringBuffer. If not use StringBuilder
	                // if we do actually need StringBuffer, it's worth documenting why.
	                StringBuffer data = new StringBuffer ("");
	                // WARNING: This method will send the response with error messages if errors are encountered
	                int statusCode = executeByObject ((ServerManager) hm, (JSONObject) jr, data, paramFlags, resp,
	                    user, password, addr, locale);

	                if (statusCode != SC_OK && statusCode != SC_BAD_REQUEST && statusCode != SC_FORBIDDEN) {
	                    ErrorHandler.dumpFFDCwithInventory (uuid + " NodesServlet.handlePut error: " + statusCode);
	                }

	                if (statusCode == SC_OK) {
	                    DMUtils.resetConnectivityMaintenanceTimer (uuid);
	                }

	                if (! resp.isCommitted ()) {
	                    RestfulUtils.sendResponse (statusCode, data.toString (), resp);
	                }
	            }
	            else if ((path.length == 2) && (("/node".equals (ctxPath)) || "/nodes".equals (ctxPath))) {
	                // URI is for a specific IMM node and ThinkServer, we will get UUID and continue
	                String uuid      = path[0].toUpperCase ();
	                String subDataId = path[1];

	                // Ensure the uuid is of a Server we are managing
	                HardwareManager hm = CoreInventoryService.getService ().getManagerByUUID (uuid);
	                // No hardware manager found, meaning there is no major component with this UUID; return not found
	                if (hm == null) {
	                    log.error ("NodesServlet hm was not found for UUID {}", uuid);
	                    RestfulUtils.sendError (resp, HttpServletResponse.SC_NOT_FOUND);

	                    return;
	                }

	                // Hardware manager found with this UUID, but it is not for a ServerManager; bad request
	                if (! (hm instanceof ServerManager)) {
	                    log.error ("NodesServlet hm was not for a ServerManager with UUID {}", uuid);
	                    RestfulUtils.sendError (resp, HttpServletResponse.SC_BAD_REQUEST);

	                    return;
	                }

	                if ((subDataId.equalsIgnoreCase (URI_PART_IMM)) ||
	                    (subDataId.equalsIgnoreCase (URI_PART_BMC)) ||
	                    (subDataId.equalsIgnoreCase (URI_MOUNT_MEDIA)) ||
	                    subDataId.equalsIgnoreCase (URI_FWUPDATE_APPLY)) {
	                    if (! (jr instanceof JSONObject)) {
	                        log.error ("Invalid format on URI command requests: non-object PUT body");
	                        RestfulUtils.sendError (resp, HttpServletResponse.SC_BAD_REQUEST);
	                        return;
	                    }

	                    int          statusCode;
	                    StringBuffer data = new StringBuffer ("");

	                    if (subDataId.equalsIgnoreCase (URI_PART_IMM) || subDataId.equalsIgnoreCase (URI_PART_BMC)) {
	                        // For Power actions clarification - common terminology
	                        // block {"powerState":"reset"} for /nodes/uuid/bmc
	                        if (subDataId.equalsIgnoreCase (URI_PART_BMC)) {
	                            String powerState = ((JSONObject) jr).getStringOrNull (PowerManagementRequest.JSON_PROP_POWERSTATE);
	                            if (powerState != null && powerState.equalsIgnoreCase (PowerManagementRequest.JSON_PROP_RESET)) {
	                                log.error (" Invalid power state request {} for BMC", powerState);
	                                JSONObject msg = MessageUtils.getJSONResponse (MessageUtils.MSG_BAD_REQUEST, MessageUtils.getString ("OPERATION_NOT_SUPPORTED", locale), SC_BAD_REQUEST, locale);
	                                RestfulUtils.sendResponse (SC_BAD_REQUEST, msg.toJson (), resp);
	                                return;
	                            }
	                        }

	                        // Proceed with the request submitted, on the hardware we have a manager for, and send the response
	                        // WARNING: This method will send the response with error messages if errors are encountered
	                        statusCode = executeByIMMObject (hm, (JSONObject) jr, data, resp,
	                            user, password, locale);               // for ITE and Rack Server and ThinkServer
	                    }
	                    else if (subDataId.equalsIgnoreCase (URI_FWUPDATE_APPLY)) {
	                        statusCode = FirmwareUpdateApplyRequest.doRequest (resp, hm, (JSONObject) jr, user, password, data, locale);
	                    }
	                    else {
	                        statusCode = MountMediaRequest.doMountMediaPutRequest ((ServerManager) hm, (JSONObject) jr, data, resp, user, password, locale);
	                    }

	                    if (statusCode != SC_OK && statusCode != SC_BAD_REQUEST && statusCode != SC_FORBIDDEN) {
	                        ErrorHandler.dumpFFDCwithInventory (uuid + " NodesServlet.handlePut error: " + statusCode);
	                    }

	                    if (! resp.isCommitted ()) {
	                        RestfulUtils.sendResponse (statusCode, data.toString (), resp);
	                    }
	                }
	                // Get the lock status on the server
	                else if (subDataId.equalsIgnoreCase (URI_PART_LOCK)) {
	                    if (! (jr instanceof JSONObject)) {
	                        log.error ("Invalid format on URI command requests: non-object PUT body");
	                        RestfulUtils.sendError (resp, HttpServletResponse.SC_BAD_REQUEST);
	                        return;
	                    }

	                    String lock = ((JSONObject) jr).getStringOrDefault (URI_PART_LOCK, "");
	                    String on   = "on";
	                    String off  = "off";

	                    if ((lock == null) || ! (lock instanceof String)) {
	                        StringBuilder sb = new StringBuilder ("");
	                        sb.append (MessageUtils.getJSONResponse (MessageUtils.MSG_BAD_REQUEST, "Invalid request", HttpServletResponse.SC_BAD_REQUEST, locale));
	                        RestfulUtils.sendResponse (HttpServletResponse.SC_BAD_REQUEST, sb.toString (), resp);
	                    }
	                    else {
	                        boolean myLock = false;
	                        if (on.equalsIgnoreCase (lock)) {
	                            myLock = true;
	                        }
	                        else if (off.equalsIgnoreCase (lock)) {
	                            myLock = false;
	                        }
	                        else {
	                            StringBuilder sb = new StringBuilder ("");
	                            sb.append (MessageUtils.getJSONResponse (MessageUtils.MSG_BAD_REQUEST, "Invalid request", HttpServletResponse.SC_BAD_REQUEST, locale));
	                            RestfulUtils.sendResponse (HttpServletResponse.SC_BAD_REQUEST, sb.toString (), resp);
	                        }

	                        ServerManager srvMgr = (ServerManager) hm;
	                        ServerLockRequest.setLock (resp, srvMgr, user, password, locale, myLock);
	                    }
	                }
	                else {
	                    log.error ("Unknown URI request for PUT path path {}, ctxPath {}", path, ctxPath);
	                    RestfulUtils.sendResponse (SC_BAD_REQUEST, "", resp);
	                    return;
	                }
	            }
	            else if ((path.length == 3) && (("/node".equals (ctxPath)) || "/nodes".equals (ctxPath))) {
	                // URI is for a specific IMM node and ThinkServer, we will get UUID and continue
	                String uuid      = path[0].toUpperCase ();
	                String subDataId = path[1];
	                String subPartId = path[2];
	                if (subDataId.equalsIgnoreCase (URI_MOUNT_MEDIA)) {
	                    log.info (" NodeServlet PUT body: {}", RestApiUtils.hidePasswordInJsonBody (body));

	                    if (! (jr instanceof JSONObject)) {
	                        log.error ("Invalid format on URI command requests: non-object PUT body");
	                        RestfulUtils.sendError (resp, HttpServletResponse.SC_BAD_REQUEST);
	                        return;
	                    }

	                    // Ensure the uuid is of a Server we are managing
	                    HardwareManager hm = CoreInventoryService.getService ().getManagerByUUID (uuid);
	                    // No hardware manager found, meaning there is no major component with this UUID; return not found
	                    if (hm == null) {
	                        log.error ("NodesServlet hm was not found for UUID {}", uuid);
	                        RestfulUtils.sendError (resp, HttpServletResponse.SC_NOT_FOUND);

	                        return;
	                    }

	                    // Hardware manager found with this UUID, but it is not for a ServerManager; bad request
	                    if (! (hm instanceof ServerManager)) {
	                        log.error ("NodesServlet hm was not for a ServerManager with UUID {}", uuid);
	                        RestfulUtils.sendError (resp, HttpServletResponse.SC_BAD_REQUEST);

	                        return;
	                    }

	                    StringBuffer data = new StringBuffer ("");
	                    JSONObject   jo   = (JSONObject) jr;

	                    int statusCode = 0;
	                    if (subPartId.equalsIgnoreCase (URI_RDOC)) {
	                        statusCode = RDOCMediaRequest.doRDOCPutRequest (hm, jo, data, resp, user, password, locale);
	                    }
	                    else {
	                        statusCode = MountMediaRequest.doMountMediaUnmountRequest (hm, jo, data, resp, user, password, locale, subPartId);
	                    }

	                    if (statusCode != SC_OK && statusCode != SC_BAD_REQUEST && statusCode != SC_FORBIDDEN) {
	                        ErrorHandler.dumpFFDCwithInventory (uuid + " NodesServlet.handlePut error: " + statusCode);
	                    }

	                    if (! resp.isCommitted ()) {
	                        RestfulUtils.sendResponse (statusCode, data.toString (), resp);
	                    }
	                }
	                else {
	                    log.error ("Unknown URI request for PUT path path {}, ctxPath {}", path, ctxPath);
	                    RestfulUtils.sendResponse (SC_BAD_REQUEST, "", resp);
	                    return;
	                }
	            }
	            else if ((path.length == 4) && (("/node".equals (ctxPath)) || "/nodes".equals (ctxPath))) {
	                // URI is for a specific IMM node and ThinkServer, we will get UUID and continue
	                String uuid      = path[0].toUpperCase ();
	                String subDataId = path[1];
	                String subPartId = path[2];
	                String rdocUID   = path[3];
	                if (subDataId.equalsIgnoreCase (URI_MOUNT_MEDIA)) {
	                    log.info (" NodeServlet PUT body: {}", RestApiUtils.hidePasswordInJsonBody (body));

	                    if (! (jr instanceof JSONObject)) {
	                        log.error ("Invalid format on URI command requests: non-object PUT body");
	                        RestfulUtils.sendError (resp, HttpServletResponse.SC_BAD_REQUEST);
	                        return;
	                    }

	                    // Ensure the uuid is of a Server we are managing
	                    HardwareManager hm = CoreInventoryService.getService ().getManagerByUUID (uuid);
	                    // No hardware manager found, meaning there is no major component with this UUID; return not found
	                    if (hm == null) {
	                        log.error ("NodesServlet hm was not found for UUID {}", uuid);
	                        RestfulUtils.sendError (resp, HttpServletResponse.SC_NOT_FOUND);
	                        return;
	                    }

	                    // Hardware manager found with this UUID, but it is not for a ServerManager; bad request
	                    if (! (hm instanceof ServerManager)) {
	                        log.error ("NodesServlet hm was not for a ServerManager with UUID {}", uuid);
	                        RestfulUtils.sendError (resp, HttpServletResponse.SC_BAD_REQUEST);
	                        return;
	                    }

	                    StringBuffer data = new StringBuffer ("");
	                    JSONObject   jo   = (JSONObject) jr;

	                    int statusCode = 0;
	                    if (subPartId.equalsIgnoreCase (URI_RDOC)) {
	                        statusCode = RDOCMediaRequest.doRDOCPutUnmountRequest (hm, jo, data, resp, user, password, locale, rdocUID);
	                    }

	                    if (statusCode != SC_OK && statusCode != SC_BAD_REQUEST && statusCode != SC_FORBIDDEN) {
	                        ErrorHandler.dumpFFDCwithInventory (uuid + " NodesServlet.handlePut error: " + statusCode);
	                    }

	                    if (! resp.isCommitted ()) {
	                        RestfulUtils.sendResponse (statusCode, data.toString (), resp);
	                    }
	                }
	                else {
	                    log.error ("Unknown URI request for PUT path path {}, ctxPath {}", path, ctxPath);
	                    RestfulUtils.sendResponse (SC_BAD_REQUEST, "", resp);
	                    return;
	                }
	            }
	            // Unknown request, reject
	            else {
	                log.error ("Unknown URI request for PUT path path {}, ctxPath {}", path, ctxPath);
	                RestfulUtils.sendResponse (SC_BAD_REQUEST, "", resp);
	                return;
	            }
	        }
	        catch (Exception e) {
	            log.error ("An exception occured in NodesServlet handlePut ", e);
	            ErrorHandler.dumpFFDCwithInventory ("NodesServlet.handlePut error: "  + e.getMessage ());
	            throw new ServletException (e);
	        }
	    } /* handlePut */
	
	
	
}
