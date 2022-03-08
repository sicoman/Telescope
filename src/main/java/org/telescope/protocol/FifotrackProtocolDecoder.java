
package org.telescope.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;

import org.telescope.javel.framework.helper.BitUtil;
import org.telescope.javel.framework.helper.Checksum;
import org.telescope.javel.framework.helper.Parser;
import org.telescope.javel.framework.helper.PatternBuilder;
import org.telescope.javel.framework.helper.UnitsConverter;
import org.telescope.model.CellTower;
import org.telescope.model.Network;
import org.telescope.model.Position;
import org.telescope.server.BaseProtocolDecoder;
import org.telescope.server.Context;
import org.telescope.server.DeviceSession;
import org.telescope.server.NetworkMessage;
import org.telescope.server.Protocol;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public class FifotrackProtocolDecoder extends BaseProtocolDecoder {

    private ByteBuf photo;

    public FifotrackProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    private static final Pattern PATTERN = new PatternBuilder()
            .text("$$")
            .number("d+,")                       // length
            .number("(d+),")                     // imei
            .number("x+,")                       // index
            .expression("[^,]+,")                // type
            .number("(d+)?,")                    // alarm
            .number("(dd)(dd)(dd)")              // date (yymmdd)
            .number("(dd)(dd)(dd),")             // time (hhmmss)
            .number("([AV]),")                   // validity
            .number("(-?d+.d+),")                // latitude
            .number("(-?d+.d+),")                // longitude
            .number("(d+),")                     // speed
            .number("(d+),")                     // course
            .number("(-?d+),")                   // altitude
            .number("(d+),")                     // odometer
            .number("d+,")                       // runtime
            .number("(x+),")                     // status
            .number("(x+)?,")                    // input
            .number("(x+)?,")                    // output
            .number("(d+)|")                     // mcc
            .number("(d+)|")                     // mnc
            .number("(x+)|")                     // lac
            .number("(x+),")                     // cid
            .number("([x|]+)")                   // adc
            .expression(",([^,]+)")              // rfid
            .expression(",([^*]*)").optional(2)  // sensors
            .any()
            .compile();

    private static final Pattern PATTERN_PHOTO = new PatternBuilder()
            .text("$$")
            .number("d+,")                       // length
            .number("(d+),")                     // imei
            .any()
            .number(",(d+),")                    // length
            .expression("([^*]+)")               // photo id
            .text("*")
            .number("xx")
            .compile();

    private static final Pattern PATTERN_PHOTO_DATA = new PatternBuilder()
            .text("$$")
            .number("d+,")                       // length
            .number("(d+),")                     // imei
            .number("x+,")                       // index
            .expression("[^,]+,")                // type
            .expression("([^,]+),")              // photo id
            .number("(d+),")                     // offset
            .number("(d+),")                     // size
            .compile();

    private static final Pattern PATTERN_RESULT = new PatternBuilder()
            .text("$$")
            .number("d+,")                       // length
            .number("(d+),")                     // imei
            .any()
            .expression(",([A-Z]+)")             // result
            .text("*")
            .number("xx")
            .compile();

    private void requestPhoto(Channel channel, SocketAddress socketAddress, String imei, String file) {
        if (channel != null) {
            String content = "1,D06," + file + "," + photo.writerIndex() + "," + Math.min(1024, photo.writableBytes());
            int length = 1 + imei.length() + 1 + content.length();
            String response = String.format("##%02d,%s,%s*", length, imei, content);
            response += Checksum.sum(response) + "\r\n";
            channel.writeAndFlush(new NetworkMessage(response, socketAddress));
        }
    }

    private String decodeAlarm(Integer alarm) {
        if (alarm != null) {
            switch (alarm) {
                case 2:
                    return Position.ALARM_SOS;
                case 14:
                    return Position.ALARM_LOW_POWER;
                case 15:
                    return Position.ALARM_POWER_CUT;
                case 16:
                    return Position.ALARM_POWER_RESTORED;
                case 17:
                    return Position.ALARM_LOW_BATTERY;
                case 18:
                    return Position.ALARM_OVERSPEED;
                case 20:
                    return Position.ALARM_GPS_ANTENNA_CUT;
                case 21:
                    return Position.ALARM_VIBRATION;
                case 23:
                    return Position.ALARM_ACCELERATION;
                case 24:
                    return Position.ALARM_BRAKING;
                case 27:
                    return Position.ALARM_FATIGUE_DRIVING;
                case 30:
                case 32:
                    return Position.ALARM_JAMMING;
                case 33:
                    return Position.ALARM_GEOFENCE_EXIT;
                case 34:
                    return Position.ALARM_GEOFENCE_ENTER;
                case 35:
                    return Position.ALARM_IDLE;
                case 40:
                case 41:
                    return Position.ALARM_TEMPERATURE;
                default:
                    return null;
            }
        }
        return null;
    }

    private Object decodeLocation(
            Channel channel, SocketAddress remoteAddress, String sentence) {

        Parser parser = new Parser(PATTERN, sentence);
        if (!parser.matches()) {
            return null;
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, parser.next());
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        position.set(Position.KEY_ALARM, decodeAlarm(parser.nextInt()));

        position.setTime(parser.nextDateTime());

        position.setValid(parser.next().equals("A"));
        position.setLatitude(parser.nextDouble());
        position.setLongitude(parser.nextDouble());
        position.setSpeed(UnitsConverter.knotsFromKph(parser.nextInt()));
        position.setCourse(parser.nextInt());
        position.setAltitude(parser.nextInt());

        position.set(Position.KEY_ODOMETER, parser.nextLong());

        long status = parser.nextHexLong();
        position.set(Position.KEY_RSSI, BitUtil.between(status, 3, 8));
        position.set(Position.KEY_SATELLITES, BitUtil.from(status, 28));
        position.set(Position.KEY_STATUS, status);

        position.set(Position.KEY_INPUT, parser.nextHexInt());
        position.set(Position.KEY_OUTPUT, parser.nextHexInt());

        position.setNetwork(new Network(CellTower.from(
                parser.nextInt(), parser.nextInt(), parser.nextHexInt(), parser.nextHexInt())));

        String[] adc = parser.next().split("\\|");
        for (int i = 0; i < adc.length; i++) {
            position.set(Position.PREFIX_ADC + (i + 1), Integer.parseInt(adc[i], 16));
        }

        if (parser.hasNext()) {
            position.set(Position.KEY_DRIVER_UNIQUE_ID, String.valueOf(parser.nextHexInt()));
        }

        if (parser.hasNext()) {
            String[] sensors = parser.next().split("\\|");
            for (int i = 0; i < sensors.length; i++) {
                position.set(Position.PREFIX_IO + (i + 1), sensors[i]);
            }
        }

        return position;
    }

    private Object decodeResult(
            Channel channel, SocketAddress remoteAddress, String sentence) {

        Parser parser = new Parser(PATTERN_RESULT, sentence);
        if (!parser.matches()) {
            return null;
        }

        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, parser.next());
        if (deviceSession == null) {
            return null;
        }

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        position.set(Position.KEY_RESULT, parser.next());

        return position;
    }

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;
        int typeIndex = buf.indexOf(buf.readerIndex(), buf.writerIndex(), (byte) ',') + 1;
        typeIndex = buf.indexOf(typeIndex, buf.writerIndex(), (byte) ',') + 1;
        typeIndex = buf.indexOf(typeIndex, buf.writerIndex(), (byte) ',') + 1;
        String type = buf.toString(typeIndex, 3, StandardCharsets.US_ASCII);

        if (type.startsWith("B")) {

            return decodeResult(channel, remoteAddress, buf.toString(StandardCharsets.US_ASCII));

        } else if (type.equals("D05")) {

            String sentence = buf.toString(StandardCharsets.US_ASCII);
            Parser parser = new Parser(PATTERN_PHOTO, sentence);
            if (parser.matches()) {
                String imei = parser.next();
                int length = parser.nextInt();
                String photoId = parser.next();
                photo = Unpooled.buffer(length);
                requestPhoto(channel, remoteAddress, imei, photoId);
            }

        } else if (type.equals("D06")) {

            if (photo == null) {
                return null;
            }
            int dataIndex = buf.indexOf(typeIndex + 4, buf.writerIndex(), (byte) ',') + 1;
            dataIndex = buf.indexOf(dataIndex, buf.writerIndex(), (byte) ',') + 1;
            dataIndex = buf.indexOf(dataIndex, buf.writerIndex(), (byte) ',') + 1;
            String sentence = buf.toString(buf.readerIndex(), dataIndex, StandardCharsets.US_ASCII);
            Parser parser = new Parser(PATTERN_PHOTO_DATA, sentence);
            if (parser.matches()) {
                String imei = parser.next();
                String photoId = parser.next();
                parser.nextInt(); // offset
                parser.nextInt(); // size
                buf.readerIndex(dataIndex);
                buf.readBytes(photo, buf.readableBytes() - 3); // ignore checksum
                if (photo.isWritable()) {
                    requestPhoto(channel, remoteAddress, imei, photoId);
                } else {
                    Position position = new Position(getProtocolName());
                    position.setDeviceId(getDeviceSession(channel, remoteAddress, imei).getDeviceId());
                    getLastLocation(position, null);
                    position.set(Position.KEY_IMAGE, Context.getMediaManager().writeFile(imei, photo, "jpg"));
                    photo.release();
                    photo = null;
                    return position;
                }
            }

        } else {

            return decodeLocation(channel, remoteAddress, buf.toString(StandardCharsets.US_ASCII));

        }

        return null;
    }

}