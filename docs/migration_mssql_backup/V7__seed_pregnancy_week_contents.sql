SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

DECLARE @now DATETIMEOFFSET(7) = SYSDATETIMEOFFSET();

;WITH pregnancy_weeks AS (
    SELECT 0 AS week
    UNION ALL
    SELECT week + 1
    FROM pregnancy_weeks
    WHERE week < 42
)
INSERT INTO app.pregnancy_week_contents (
    week,
    title,
    summary,
    baby_development,
    mother_changes,
    care_tips,
    warning_signs,
    sources,
    disclaimer,
    created_at,
    updated_at
)
SELECT
    pregnancy_weeks.week,
    CONCAT(N'Tuần thai kỳ ', pregnancy_weeks.week),
    CONCAT(
        N'Nội dung tổng quan cho tuần ', pregnancy_weeks.week,
        N' đang chờ đội ngũ chuyên môn y khoa của NutriMom thẩm định.'),
    CONCAT(
        N'Thông tin phát triển của em bé ở tuần ', pregnancy_weeks.week,
        N' đang chờ thẩm định y khoa trước khi phát hành nội dung chi tiết.'),
    CONCAT(
        N'Những thay đổi thường gặp của mẹ ở tuần ', pregnancy_weeks.week,
        N' có thể khác nhau giữa từng người và đang chờ thẩm định y khoa.'),
    N'Duy trì lịch khám thai và trao đổi trực tiếp với bác sĩ hoặc cơ sở y tế '
        + N'về chế độ chăm sóc phù hợp với tình trạng cá nhân.',
    N'Nếu xuất hiện triệu chứng bất thường, nghiêm trọng hoặc khiến bạn lo lắng, '
        + N'hãy liên hệ bác sĩ hoặc cơ sở y tế; không tự chẩn đoán từ nội dung này.',
    N'[{"name":"World Health Organization - Pregnancy",'
        + N'"url":"https://www.who.int/health-topics/pregnancy"}]',
    N'Nội dung chỉ nhằm cung cấp thông tin tham khảo, đang chờ rà soát y khoa '
        + N'và không thay thế chẩn đoán hay chỉ định của bác sĩ. '
        + N'Hãy tham khảo bác sĩ hoặc cơ sở y tế để được tư vấn phù hợp.',
    @now,
    @now
FROM pregnancy_weeks
WHERE NOT EXISTS (
    SELECT 1
    FROM app.pregnancy_week_contents existing
    WHERE existing.week = pregnancy_weeks.week
)
OPTION (MAXRECURSION 43);
GO
