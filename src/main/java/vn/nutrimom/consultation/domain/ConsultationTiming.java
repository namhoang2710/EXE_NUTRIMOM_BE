package vn.nutrimom.consultation.domain;

import java.time.*;

/** Single domain clock policy; appointment day does not change queue acceptance deadlines. */
public final class ConsultationTiming {
    public static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
    public static final int EARLY_MINUTES=5, LATE_GRACE_MINUTES=3;
    private ConsultationTiming() { }
    public static OffsetDateTime acceptanceDeadline(OffsetDateTime opened) {
        var local=opened.atZoneSameInstant(ZONE);
        var time=local.toLocalTime();
        if(time.isBefore(LocalTime.of(8,0))) return local.toLocalDate().atTime(8,30).atZone(ZONE).toOffsetDateTime();
        if(!time.isBefore(LocalTime.of(19,0))) return local.toLocalDate().plusDays(1).atTime(8,30).atZone(ZONE).toOffsetDateTime();
        return opened.plusMinutes(30);
    }
    public static boolean onTime(Instant firstJoin,Instant start) {
        return firstJoin!=null && !firstJoin.isBefore(start.minusSeconds(EARLY_MINUTES*60L))
                && !firstJoin.isAfter(start.plusSeconds(LATE_GRACE_MINUTES*60L));
    }
    public static String deadlineMessage(OffsetDateTime deadline,OffsetDateTime now) {
        var day=deadline.atZoneSameInstant(ZONE).toLocalDate();
        var today=now.atZoneSameInstant(ZONE).toLocalDate();
        String relative=day.equals(today)?"hôm nay":day.equals(today.plusDays(1))?"ngày mai":"";
        return "Yêu cầu tư vấn của bạn cần được tiếp nhận chậm nhất lúc "
                +deadline.atZoneSameInstant(ZONE).toLocalTime().toString()+" "+relative+" ("
                +day.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))+ ").";
    }
}
