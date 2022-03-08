
package org.telescope.protocol;

import io.netty.channel.Channel;

import org.telescope.javel.framework.helper.Parser;
import org.telescope.javel.framework.helper.PatternBuilder;
import org.telescope.javel.framework.helper.UnitsConverter;
import org.telescope.model.Position;
import org.telescope.server.BaseProtocolDecoder;
import org.telescope.server.DeviceSession;
import org.telescope.server.Protocol;

import java.net.SocketAddress;
import java.util.regex.Pattern;

public class TelicProtocolDecoder extends BaseProtocolDecoder {

    public TelicProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private static final Pattern PATTERN = new PatternBuilder()
            .number("dddd")
            .number("(d{6}|d{15})")              // device id
            .number("(d{1,2}),")                 // type
            .number("d{12},")                    // event time
            .number("d+,")
            .number("(dd)(dd)(dd)")              // date (ddmmyy)
            .number("(dd)(dd)(dd),")             // time (hhmmss)
            .groupBegin()
            .number("(-?d{8,}),")                // longitude
            .number("(-?d{7,}),")                // latitude
            .or()
            .number("(-?d+),")                   // longitude
            .number("(-?d+),")                   // latitude
            .groupEnd()
            .number("(d),")                      // validity
            .number("(d+),")                     // speed
            .number("(d+),")                     // course
            .number("(d+)?,")                    // satellites
            .expression("(?:[^,]*,){7}")
            .number("(d+),")                     // battery
            .any()
            .compile();

    private String decodeAlarm(int eventId) {

        switch (eventId) {
            case 1:
                return Position.ALARM_POWER_ON;
            case 2:
                return Position.ALARM_SOS;
            case 5:
                return Position.ALARM_POWER_OFF;
            case 7:
                return Position.ALARM_GEOFENCE_ENTER;
            case 8:
                return Position.ALARM_GEOFENCE_EXIT;
            case 22:
                return Position.ALARM_LOW_BATTERY;
            case 25:
                return Position.ALARM_MOVEMENT;
            default:
                return null;
        }
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        Parser parser = new Parser(PATTERN, (String) msg);
        if (!parser.matches()) {
            return null;
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, parser.next());
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        int event = parser.nextInt();
        position.set(Position.KEY_EVENT, event);
        position.set(Position.KEY_ALARM, decodeAlarm(event));

        if (event == 11) {
            position.set(Position.KEY_IGNITION, true);
        } else if (event == 12) {
            position.set(Position.KEY_IGNITION, false);
        }

        position.setTime(parser.nextDateTime(Parser.DateTimeFormat.DMY_HMS));

        if (parser.hasNext(2)) {
            position.setLongitude(parser.nextDouble() / 1000000);
            position.setLatitude(parser.nextDouble() / 1000000);
        }

        if (parser.hasNext(2)) {
            position.setLongitude(parser.nextDouble() / 10000);
            position.setLatitude(parser.nextDouble() / 10000);
        }

        position.setValid(parser.nextInt() != 1);
        position.setSpeed(UnitsConverter.knotsFromKph(parser.nextDouble()));
        position.setCourse(parser.nextDouble());

        position.set(Position.KEY_SATELLITES, parser.nextInt());
        position.set(Position.KEY_BATTERY, parser.nextInt());

        return position;
    }

}