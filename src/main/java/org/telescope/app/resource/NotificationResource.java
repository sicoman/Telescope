
package org.telescope.app.resource;

import java.util.Collection;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.telescope.app.ExtendedObjectResource;
import org.telescope.javel.framework.notification.MessageException;
import org.telescope.model.Event;
import org.telescope.model.Notification;
import org.telescope.model.Typed;
import org.telescope.server.Context;


@Path("notifications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class NotificationResource extends ExtendedObjectResource<Notification> {

    public NotificationResource() {
        super(Notification.class);
    }

    @GET
    @Path("types")
    public Collection<Typed> get() {
        return Context.getNotificationManager().getAllNotificationTypes();
    }

    @GET
    @Path("notificators")
    public Collection<Typed> getNotificators() {
        return Context.getNotificatorManager().getAllNotificatorTypes();
    }

    @POST
    @Path("test")
    public Response testMessage() throws MessageException, InterruptedException {
        for (Typed method : Context.getNotificatorManager().getAllNotificatorTypes()) {
            Context.getNotificatorManager()
                    .getNotificator(method.getType()).sendSync(getUserId(), new Event("test", 0), null);
        }
        return Response.noContent().build();
    }

    @POST
    @Path("test/{notificator}")
    public Response testMessage(@PathParam("notificator") String notificator)
            throws MessageException, InterruptedException {
        Context.getNotificatorManager().getNotificator(notificator).sendSync(getUserId(), new Event("test", 0), null);
        return Response.noContent().build();
    }

}