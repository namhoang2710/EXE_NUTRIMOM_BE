package vn.nutrimom.admin.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import vn.nutrimom.admin.dto.AdminOperationsDtos.*;
import vn.nutrimom.admin.model.AdminQuery;
import vn.nutrimom.admin.repository.AdminOperationsRepository;
import vn.nutrimom.admin.repository.AdminOperationsRepository.Filter;
import static vn.nutrimom.admin.repository.AdminOperationsRepository.*;
import static vn.nutrimom.admin.service.AdminOperationsService.*;

@Service
public class AdminReportService {
    private static final Logger log=LoggerFactory.getLogger(AdminReportService.class);
    private static final int EXPORT_MAX=10_000,CHUNK_SIZE=500;
    private final AdminOperationsRepository db;
    private final Clock clock;
    private final AdminExpertPerformanceService performance;
    private final ExpertScheduleKpiService schedules;
    public AdminReportService(AdminOperationsRepository db,Clock clock,AdminExpertPerformanceService performance) {
        this(db,clock,performance,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AdminReportService(AdminOperationsRepository db,Clock clock,AdminExpertPerformanceService performance,ExpertScheduleKpiService schedules) {
        this.db=db;this.clock=clock;this.performance=performance;this.schedules=schedules;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
    public Report report(String type,LocalDate from,LocalDate to,String source,int page,int size,String specialty,String expert,int version) {
        validateVersion(type,source,version);
        return version==3?schedules.report(from,to,specialty,expert,page,size,false):report(type,from,to,source,page,size,specialty,expert);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
    public byte[] export(String type,LocalDate from,LocalDate to,String source,String specialty,String expert,int version) {
        validateVersion(type,source,version);
        if(version==2) return export(type,from,to,source,specialty,expert);
        var report=schedules.report(from,to,specialty,expert,1,EXPORT_MAX,true);
        StringBuilder csv=new StringBuilder("\uFEFF");
        var headers=new ArrayList<Object>(List.of("Mã chuyên gia","Tên","Chuyên khoa","Khung chuẩn 30 phút","Khung làm việc","Khung nghỉ",
                "Ngày nghỉ","Khung đóng ngoài ngày nghỉ","Đúng giờ","Trễ giờ","Chưa check-in","Chưa có bằng chứng check-in"));
        for(String metric:List.of("Chuyên cần","Đúng giờ","Hài lòng","Hoàn thành","Tiếp nhận đúng hạn","Khách quay lại"))
            for(String field:List.of("tử số","mẫu số","%","thiếu bằng chứng","trạng thái","kỳ trước tử số","kỳ trước mẫu số","kỳ trước %","chênh lệch điểm %")) headers.add(metric+" "+field);
        headers.addAll(List.of("Từ ngày","Đến ngày","Kỳ trước từ","Kỳ trước đến","Múi giờ","Thời điểm chụp","Phiên bản tính",
            "Ngày đầu dữ liệu trong DB","Kỳ này có dữ liệu từ","Kỳ trước có dữ liệu từ","Kỳ này đủ kỳ","Kỳ trước đủ kỳ","Trạng thái so sánh"));
        append(csv,headers);
        for(Object item:report.items()) {
            var row=(ExpertScheduleKpiService.ExpertRow)item;var s=row.scheduleSummary();var c=row.checkinSummary();
            var values=new ArrayList<Object>(List.of(row.expert().userId(),row.expert().fullName(),row.expert().specialty(),
                    s.standardSlots(),s.availableSlots(),s.offSlots(),s.dayOffDays(),s.closedSlots(),c.onTimeConsultations(),c.lateConsultations(),c.notCheckedInConsultations(),c.unknownConsultations()));
            for(var axis:row.axes()) values.addAll(Arrays.asList(axis.current().numerator(),axis.current().denominator(),axis.current().percent(),axis.current().missingEvidence(),
                    axis.current().status(),axis.previous().numerator(),axis.previous().denominator(),axis.previous().percent(),axis.changePercentagePoints()));
            values.addAll(List.of(report.period().fromDate(),report.period().toDate(),report.period().previousFromDate(),report.period().previousToDate(),report.timezone(),report.generatedAt(),3));
            var coverage=row.dataCoverage();
            values.addAll(Arrays.asList(coverage.filterMinDate(),coverage.currentEffectiveFromDate(),coverage.previousEffectiveFromDate(),
                coverage.currentPeriodComplete(),coverage.previousPeriodComplete(),coverage.comparisonStatus()));
            append(csv,values);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void validateVersion(String type,String source,int version) {
        if(version!=2 && version!=3) throw AdminQuery.invalid("calculationVersion","Chỉ hỗ trợ báo cáo phiên bản 2 hoặc 3.");
        if(version==3 && !"EXPERT_PERFORMANCE".equals(type)) throw AdminQuery.invalid("calculationVersion","Phiên bản 3 chỉ áp dụng cho hiệu suất chuyên gia.");
        if("EXPERT_PERFORMANCE".equals(type) && source!=null) throw AdminQuery.invalid("source","Báo cáo hiệu suất không dùng nguồn thanh toán.");
    }
    private record Context(String type,OffsetDateTime now,Period period,Filter filter,String from) {}
    private Context context(String type,LocalDate from,LocalDate to,String source) {
        AdminQuery.option("type",type,Set.of("OPERATIONS","USERS_SUBSCRIPTIONS","REVENUE"),null);
        if(!"REVENUE".equals(type) && source!=null) throw AdminQuery.invalid("source","Chỉ báo cáo tiền thu hỗ trợ bộ lọc nguồn.");
        var now=OffsetDateTime.now(clock).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var period=AdminQuery.period(from,to,clock,true,false);
        return switch(type) {
            case "OPERATIONS"->new Context(type,now,period,db.appointments(null,Set.of(),null,null,period,"CREATED",false,now),APPOINTMENT_FROM);
            case "USERS_SUBSCRIPTIONS"->new Context(type,now,period,db.registeredFilter(period,now),REGISTERED_FROM);
            default->new Context(type,now,period,db.payments(null,null,"PAID",
                    AdminQuery.option("source",source,SOURCES,"ALL"),period,"PAID",now),PAYMENT_FROM);
        };
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Report report(String type,LocalDate from,LocalDate to,String source,int page,int size) {
        AdminQuery.page(page,size);var c=context(type,from,to,source);long total=db.count(c.from(),c.filter());
        long offset=(long)(page-1)*size;
        List<?> items=offset>Integer.MAX_VALUE ? List.of() : items(c,(int)offset,size);
        return new Report(c.type(),c.now(),AdminQuery.ZONE.getId(),c.period(),summary(c),items,page,size,
                total,(int)Math.min(Integer.MAX_VALUE,(total+size-1)/size));
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public byte[] export(String type,LocalDate from,LocalDate to,String source) {
        long start=System.nanoTime();var c=context(type,from,to,source);long count=db.count(c.from(),c.filter());
        if(count>EXPORT_MAX) throw AdminQuery.invalid("fromDate","Báo cáo vượt 10000 dòng. Vui lòng thu hẹp khoảng ngày.");
        StringBuilder csv=new StringBuilder("\uFEFF");append(csv,headers(type));
        // Bounded 10k-row buffer prevents sending partial CSV before a query or validation failure.
        for(int offset=0;offset<count;offset+=CHUNK_SIZE) {
            if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("Report export interrupted");
            for(Object row:items(c,offset,CHUNK_SIZE)) append(csv,values(row));
        }
        log.info("Admin report export type={} rows={} durationMs={}",type,count,(System.nanoTime()-start)/1_000_000);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
    public Report report(String type,LocalDate from,LocalDate to,String source,int page,int size,String specialty,String expert) {
        if(!type.equals("EXPERT_PERFORMANCE")) {
            if(specialty!=null || expert!=null) throw AdminQuery.invalid("specialty","Bộ lọc chuyên gia chỉ áp dụng cho báo cáo hiệu suất.");
            return report(type,from,to,source,page,size);
        }
        if(source!=null) throw AdminQuery.invalid("source","Báo cáo hiệu suất không dùng nguồn thanh toán.");
        return performance.report(from,to,specialty,expert,page,size,false);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,timeout=15)
    public byte[] export(String type,LocalDate from,LocalDate to,String source,String specialty,String expert) {
        if(!type.equals("EXPERT_PERFORMANCE")) {
            if(specialty!=null || expert!=null) throw AdminQuery.invalid("specialty","Bộ lọc chuyên gia chỉ áp dụng cho báo cáo hiệu suất.");
            return export(type,from,to,source);
        }
        if(source!=null) throw AdminQuery.invalid("source","Báo cáo hiệu suất không dùng nguồn thanh toán.");
        var report=performance.report(from,to,specialty,expert,1,EXPORT_MAX,true);
        StringBuilder csv=new StringBuilder("\uFEFF");
        var headers=new ArrayList<Object>(List.of("Mã chuyên gia","Tên","Chuyên khoa","Trạng thái","Hoàn thành","Điểm đánh giá","Vắng","Trễ","Chưa rõ chuyên cần"));
        for(String metric:List.of("Chuyên cần","Đúng giờ","Hài lòng","Hoàn thành","Tiếp nhận đúng hạn","Khách quay lại"))
            for(String field:List.of("tử số","mẫu số","%","thiếu bằng chứng","trạng thái","kỳ trước tử số","kỳ trước mẫu số","kỳ trước %","chênh lệch điểm %")) headers.add(metric+" "+field);
        headers.addAll(List.of("Từ ngày","Đến ngày","Kỳ trước từ","Kỳ trước đến","Múi giờ","Thời điểm chụp","Phiên bản tính"));
        append(csv,headers);
        for(Object item:report.items()) {
            var row=(AdminExpertPerformanceService.ExpertReportRow)item;var summary=row.summary();
            var values=new ArrayList<Object>(Arrays.asList(row.expert().userId(),row.expert().fullName(),row.expert().specialty(),row.expert().expertStatus(),
                    summary.completedConsultations(),summary.averageRating(),summary.absentConsultations(),summary.lateConsultations(),summary.unknownAttendanceConsultations()));
            for(var axis:row.axes()) values.addAll(Arrays.asList(axis.current().numerator(),axis.current().denominator(),axis.current().percent(),
                    axis.current().missingEvidence(),axis.current().status(),axis.previous().numerator(),axis.previous().denominator(),axis.previous().percent(),axis.changePercentagePoints()));
            values.addAll(List.of(report.period().fromDate(),report.period().toDate(),report.period().previousFromDate(),report.period().previousToDate(),report.timezone(),report.generatedAt(),2));
            append(csv,values);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private List<?> items(Context c,int offset,int size) {
        return switch(c.type()) {
            case "OPERATIONS"->db.reportAppointments(c.filter(),offset,size,c.now());
            case "USERS_SUBSCRIPTIONS"->db.reportUsers(c.filter(),offset,size);
            default->db.reportPayments(c.filter(),offset,size);
        };
    }
    private Map<String,Object> summary(Context c) {
        if(c.type().equals("USERS_SUBSCRIPTIONS")) return db.registeredTotals(c.filter());
        if(c.type().equals("REVENUE")) {
            var totals=db.paymentTotals(c.filter());
            long amount=number(totals,"amount"),count=number(totals,"count");
            String source=c.filter().params().hasValue("source") ? c.filter().params().getValue("source").toString() : "ALL";
            return Map.of("source",source,"collected_amount",amount,"paid_orders",count,"average_paid_amount",average(amount,count));
        }
        return db.one("""
                select count(*) created_requests,
                  coalesce(sum(case when c.status='PENDING_EXPERT' then 1 else 0 end),0) pending_expert,
                  coalesce(sum(case when c.status='PENDING_CONSULTATION' then 1 else 0 end),0) pending_consultation,
                  coalesce(sum(case when c.status='COMPLETED' then 1 else 0 end),0) completed_in_cohort,
                  coalesce(sum(case when c.status='CANCELLED' then 1 else 0 end),0) cancelled_in_cohort,
                  coalesce(sum(case when
                """+PAST_END+" then 1 else 0 end),0) past_end_uncompleted "+APPOINTMENT_FROM+c.filter().where(),c.filter().params());
    }
    private List<?> headers(String type) {
        return switch(type) {
            case "OPERATIONS"->List.of("Mã yêu cầu","Người dùng","Chuyên gia","Chuyên khoa","Trạng thái","Ngày tạo",
                    "Ngày tư vấn","Giờ bắt đầu","Giờ kết thúc","Hoàn tất lúc","Giờ chờ","Cần chú ý","Điểm đánh giá");
            case "USERS_SUBSCRIPTIONS"->List.of("Mã người dùng","Tên","Ngày đăng ký","Trạng thái tài khoản","Gói hiện tại","Hết hạn lúc");
            default->List.of("Mã đơn","Người mua","Gói","Số tiền VND","Nguồn","Trạng thái","Ngày tạo","Ngày thanh toán");
        };
    }
    private List<?> values(Object row) {
        if(row instanceof Appointment a) return Arrays.asList(a.id(),a.userDisplayName(),a.expertName(),a.specialty(),
                a.status(),a.createdAt(),a.slot()==null?null:a.slot().slotDate(),
                a.slot()==null?null:a.slot().startTime(),a.slot()==null?null:a.slot().endTime(),a.completedAt(),
                a.waitingHours(),a.needsAttention()?"Có":"Không",a.reviewRating());
        if(row instanceof RegisteredUser u) return Arrays.asList(u.userId(),u.displayName(),u.createdAt(),u.accountStatus(),
                u.currentPlanTier(),u.subscriptionEndDate());
        var p=(PaymentOrder)row;
        return Arrays.asList(p.orderCode(),p.userDisplayName(),p.planName(),p.amount(),p.source(),p.status(),p.createdAt(),p.paidAt());
    }
    private static void append(StringBuilder csv,List<?> values) {
        for(int i=0;i<values.size();i++) {if(i>0)csv.append(',');csv.append(cell(values.get(i)));}
        csv.append("\r\n");
    }
    static String cell(Object value) {
        if(value==null)return "\"\"";
        if(value instanceof Number)return value.toString();
        String text=value instanceof OffsetDateTime at ? at.atZoneSameInstant(AdminQuery.ZONE).toOffsetDateTime().toString() : value.toString();
        int first=0;
        while(first<text.length() && (Character.isWhitespace(text.charAt(first))||Character.isISOControl(text.charAt(first)))) first++;
        if(first<text.length() && "=+-@".indexOf(text.charAt(first))>=0)text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }
}
