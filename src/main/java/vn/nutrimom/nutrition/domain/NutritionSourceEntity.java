package vn.nutrimom.nutrition.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/**
 * Nguồn của một số liệu dinh dưỡng (bảng {@code app.nutrition_sources}, migration V57).
 *
 * <p>Tồn tại để mỗi giá trị vi chất trả lời được câu "con số này lấy ở đâu ra".
 * {@code recordRef} là mã bản ghi trong nguồn (vd FDC ID của USDA) để tra ngược đúng dòng.</p>
 */
@Entity
@Table(name = "nutrition_sources", schema = "app")
public class NutritionSourceEntity {

    @Id
    @Column(name = "id", nullable = false, length = 64)
    private String id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "record_ref", length = 255)
    private String recordRef;

    @Column(name = "retrieved_on")
    private LocalDate retrievedOn;

    public String getId() { return id; }
    public void setId(String value) { id = value; }

    public String getName() { return name; }
    public void setName(String value) { name = value; }

    public String getRecordRef() { return recordRef; }
    public void setRecordRef(String value) { recordRef = value; }

    public LocalDate getRetrievedOn() { return retrievedOn; }
    public void setRetrievedOn(LocalDate value) { retrievedOn = value; }
}
