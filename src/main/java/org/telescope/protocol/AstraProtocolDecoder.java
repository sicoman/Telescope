
package org.telescope.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telescope.javel.framework.helper.BitUtil;
import org.telescope.javel.framework.helper.DateBuilder;
import org.telescope.javel.framework.helper.UnitsConverter;
import org.telescope.model.Position;
import org.telescope.server.BaseProtocolDecoder;
import org.telescope.server.DeviceSession;
import org.telescope.server.NetworkMessage;
import org.telescope.server.Protocol;

import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.List;

public class AstraProtocolDecoder extends BaseProtocolDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AstraProtocolDecoder.class);

    public AstraProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    public static final int MSG_HEARTBEAT = 0x1A;
    public static final int MSG_DATA = 0x10;

    @Override
    protected Object decode(
            Channel channel, SocketAddress remoteAddress, Object msg) throws Exception {

        ByteBuf buf = (ByteBuf) msg;

        if (channel != null) {
            channel.writeAndFlush(new NetworkMessage(Unpooled.wrappedBuffer(new byte[] {0x06}), remoteAddress));
        }

        buf.readUnsignedByte(); // protocol
        buf.readUnsignedShort(); // length

        String imei = String.format("%08d", buf.readUnsignedInt()) + String.format("%07d", buf.readUnsignedMedium());
        DeviceSession deviceSession = getDeviceSession(channel, remoteAddress, imei);
        if (deviceSession == null) {
            return null;
        }

        List<Position> positions = new LinkedList<>();

        while (buf.readableBytes() > 2) {

            Position position = new Position(getProtocolName());
            position.setDeviceId(deviceSession.getDeviceId());

            buf.readUnsignedByte(); // index

            position.setValid(true);
            position.setLatitude(buf.readInt() * 0.000001);
            position.setLongitude(buf.readInt() * 0.000001);

            DateBuilder dateBuilder = new DateBuilder()
                    .setDate(1980, 1, 6).addMillis(buf.readUnsignedInt() * 1000L);
            position.setTime(dateBuilder.getDate());

            position.setSpeed(UnitsConverter.knotsFromKph(buf.readUnsignedByte() * 2));
            position.setCourse(buf.readUnsignedByte() * 2);

            int reason = buf.readUnsignedMedium();
            position.set(Position.KEY_EVENT, reason);

            int status = buf.readUnsignedShort();
            position.set(Position.KEY_STATUS, status);

            position.set(Position.PREFIX_IO + 1, buf.readUnsignedByte());
            position.set(Position.PREFIX_ADC + 1, buf.readUnsignedByte());
            position.set(Position.KEY_BATTERY, buf.readUnsignedByte());
            position.set(Position.KEY_POWER, buf.readUnsignedByte());

            buf.readUnsignedByte(); // max journey speed
            buf.skipBytes(6); // accelerometer
            position.set(Position.KEY_ODOMETER_TRIP, buf.readUnsignedShort());
            buf.readUnsignedShort(); // journey idle time

            position.setAltitude(buf.readUnsignedByte() * 20);

            int quality = buf.readUnsignedByte();
            position.set(Position.KEY_SATELLITES, quality & 0xf);
            position.set(Position.KEY_RSSI, quality >> 4);

            buf.readUnsignedByte(); // geofence events

            if (BitUtil.check(status, 8)) {
                position.set(Position.KEY_DRIVER_UNIQUE_ID, buf.readSlice(7).toString(StandardCharsets.US_ASCII));
                position.set(Position.KEY_ODOMETER, buf.readUnsignedMedium() * 1000);
                position.set(Position.KEY_HOURS, UnitsConverter.msFromHours(buf.readUnsignedShort()));
            }

            if (BitUtil.check(status, 6)) {
                LOGGER.warn("Extension data is not supported");
                return position;
            }

            positions.add(position);

        }

        return positions;
    }

}