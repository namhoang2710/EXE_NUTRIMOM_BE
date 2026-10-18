package vn.nutrimom.nutrition.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import vn.nutrimom.common.exception.BusinessException;
import vn.nutrimom.common.exception.ErrorCode;
import vn.nutrimom.nutrition.domain.AllergenStatus;
import vn.nutrimom.nutrition.domain.CoverageStatus;
import vn.nutrimom.nutrition.domain.FoodGroup;
import vn.nutrimom.nutrition.domain.FoodState;
import vn.nutrimom.nutrition.domain.IngredientEntity;
import vn.nutrimom.nutrition.domain.MealPlanEntity;
import vn.nutrimom.nutrition.domain.MealPlanItemEntity;
import vn.nutrimom.nutrition.domain.MealSlotEntity;
import vn.nutrimom.nutrition.domain.PriceEstimateEntity;
import vn.nutrimom.nutrition.domain.RecommendationReason;
import vn.nutrimom.nutrition.domain.ShoppingItemState;
import vn.nutrimom.nutrition.domain.ShoppingListEntity;
import vn.nutrimom.nutrition.domain.ShoppingListItemEntity;
import vn.nutrimom.nutrition.dto.ShoppingListDtos.CustomLineRequest;
import vn.nutrimom.nutrition.dto.ShoppingListDtos.PatchShoppingItemRequest;
import vn.nutrimom.nutrition.dto.ShoppingListDtos.ShoppingGroupView;
import vn.nutrimom.nutrition.dto.ShoppingListDtos.ShoppingItemView;
import vn.nutrimom.nutrition.dto.ShoppingListDtos.ShoppingListView;
import vn.nutrimom.nutrition.dto.ShoppingListDtos.ShoppingUsageView;
import vn.nutrimom.nutrition.repository.IngredientRepository;
import vn.nutrimom.nutrition.repository.MealPlanRepository;
import vn.nutrimom.nutrition.repository.PriceEstimateRepository;
import vn.nutrimom.nutrition.repository.ShoppingListItemRepository;
import vn.nutrimom.nutrition.repository.ShoppingListRepository;
import vn.nutrimom.nutrition.service.NutritionSnapshots.RecipeSnapshot;
import vn.nutrimom.nutrition.service.ShoppingAggregator.AggregatedLine;
import vn.nutrimom.nutrition.service.ShoppingAggregator.PlannedPortion;
import vn.nutrimom.nutrition.service.ShoppingAggregator.Usage;

/**
 * Danh sách mua sắm của một thực đơn (nhánh 7).
 *
 * <h2>Hai nửa của một dòng, hai vòng đời khác nhau</h2>
 *
 * <p><strong>Lượng cần</strong> là hàm thuần của các mục kế hoạch: cùng một thực đơn luôn cho ra
 * cùng một tập dòng với cùng những con số. Nó được dựng lại từ {@link ShoppingAggregator} mỗi khi
 * {@code plan_version} của kế hoạch khác với con số danh sách đang giữ.</p>
 *
 * <p><strong>Thao tác của người dùng</strong> — đã có bao nhiêu, đã mua bao nhiêu, dòng tự nhập —
 * thì không dựng lại được từ đâu cả, nên nó nằm trong bảng và <strong>sống sót qua mọi lần đồng
 * bộ</strong>. Đó là lý do đổi món không reset danh sách: dòng cũ ở nguyên chỗ cũ với dấu đã mua
 * của nó, chỉ lượng cần được viết lại, và phần dôi ra hiện thành "cần thêm" (TC22).</p>
 *
 * <h2>Vì sao một GET lại ghi dữ liệu</h2>
 *
 * <p>Mở màn hình mua sắm là lúc duy nhất chắc chắn người dùng cần danh sách khớp thực đơn, nên
 * đồng bộ xảy ra ở đó. Phép đồng bộ là <em>idempotent</em>: chạy lại trên cùng một kế hoạch cho ra
 * đúng cùng một trạng thái, không sinh dòng thứ hai, không đổi gì nếu chưa có gì đổi. Với người
 * dùng đây vẫn là "mở ra xem", nên nó vẫn là GET.</p>
 *
 * <h2>Ba thứ lớp này cố ý không làm</h2>
 *
 * <ul>
 *   <li><strong>Không quy đổi đơn vị chưa có cơ sở.</strong> Mọi lượng nguyên liệu là gram vì công
 *       thức ghi gram; đầu vào của người dùng chỉ nhận đơn vị trong {@link MassUnits}. "Thìa" bị
 *       từ chối kèm tên trường, không bị đoán thành một số gram.</li>
 *   <li><strong>Không coi thiếu giá là 0 đồng.</strong> Dòng chưa có giá hiện "chưa có giá" và
 *       tổng mang nhãn {@link CoverageStatus#PARTIAL}.</li>
 *   <li><strong>Không để dòng tự nhập chạm vào dinh dưỡng.</strong> Dòng đó không trỏ tới nguyên
 *       liệu nào, nên không có đường nào để nó lọt vào một phép tính của thực đơn (bất biến 3).</li>
 * </ul>
 */
@Service
public class ShoppingListService {

    private static final int QUANTITY_SCALE = 2;
    private static final int MONEY_SCALE = 2;

    private final MealPlanRepository plans;
    private final ShoppingListRepository lists;
    private final ShoppingListItemRepository listItems;
    private final ShoppingAggregator aggregator;
    private final IngredientRepository ingredients;
    private final PriceEstimateRepository prices;
    private final ObjectMapper json;

    public ShoppingListService(MealPlanRepository plans, ShoppingListRepository lists,
                               ShoppingListItemRepository listItems, ShoppingAggregator aggregator,
                               IngredientRepository ingredients, PriceEstimateRepository prices,
                               ObjectMapper json) {
        this.plans = plans;
        this.lists = lists;
        this.listItems = listItems;
        this.aggregator = aggregator;
        this.ingredients = ingredients;
        this.prices = prices;
        this.json = json;
    }

    // =========================================================================================
    // GET /api/v1/meal-plans/{planId}/shopping-list
    // =========================================================================================

    /**
     * Danh sách mua sắm của một phạm vi ngày trong kế hoạch.
     *
     * @param from            bỏ trống = ngày đầu của kế hoạch
     * @param to              bỏ trống = ngày cuối của kế hoạch
     * @param excludedItemIds các mục kế hoạch người dùng <strong>bỏ chọn</strong> ở lần xem này
     */
    @Transactional
    public ShoppingListView view(String userId, String planId, LocalDate from, LocalDate to,
                                 Set<String> excludedItemIds) {
        Synced synced = loadAndSync(userId, planId, from, to);
        return render(synced, excludedItemIds);
    }

    // =========================================================================================
    // POST /api/v1/meal-plans/{planId}/shopping-list/items
    // =========================================================================================

    /**
     * Thêm một dòng tự nhập.
     *
     * <p>Dòng này nằm ngoài thực đơn: không mã nguyên liệu, không trạng thái chế biến, không giá,
     * và <strong>không ảnh hưởng tổng dinh dưỡng</strong>. Nó cũng không bị lượt đồng bộ nào xóa
     * đi, vì nó không đến từ kế hoạch nên không có kế hoạch nào lấy nó lại được.</p>
     */
    @Transactional
    public ShoppingListView addCustomLine(String userId, String planId, LocalDate from,
                                          LocalDate to, CustomLineRequest request) {
        Synced synced = loadAndSync(userId, planId, from, to);

        ShoppingListItemEntity row = new ShoppingListItemEntity();
        row.setState(ShoppingItemState.NEEDED);
        row.setCustomText(request.text().trim());
        row.setQuantity(scaled(request.quantity(), QUANTITY_SCALE));
        row.setUnit(request.quantity() == null ? null : blankToNull(request.unit()));
        synced.list().add(row);
        lists.saveAndFlush(synced.list());

        return render(synced, Set.of());
    }

    // =========================================================================================
    // PATCH / DELETE /api/v1/shopping-list-items/{itemId}
    // =========================================================================================

    /**
     * Đánh dấu đã có / đã mua, hoặc sửa chữ của một dòng tự nhập.
     *
     * <p>Trả về <strong>cả danh sách</strong> chứ không riêng dòng vừa sửa: tick một dòng làm đổi
     * tổng còn phải mua, và để giao diện tự cộng lại tổng là tạo ra nguồn sự thật thứ hai cho con
     * số tiền (cùng lý do nhánh 6 trả cả kế hoạch sau mỗi thao tác sửa).</p>
     *
     * <p>Không đòi {@code expected_plan_version}: đánh dấu đã mua không tranh chấp với một thao tác
     * sửa thực đơn — hai người cùng nhà tick cùng một dòng thì kết quả là dòng đó đã mua, đúng ý cả
     * hai. Lượng cần thì lượt đồng bộ ngay trước đó đã kéo về bản mới nhất.</p>
     */
    @Transactional
    public ShoppingListView patch(String userId, String itemId, PatchShoppingItemRequest request) {
        if (request.changesNothing()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Yêu cầu không nêu thay đổi nào: cần state, have_quantity, purchased_quantity "
                            + "hoặc custom_text.",
                    Map.of("state", "cần ít nhất một trường thay đổi"));
        }

        ShoppingListItemEntity row = requireRow(userId, itemId);
        Synced synced = loadAndSync(userId, row.getList().getPlanId(),
                row.getList().getStartDate(), row.getList().getEndDate());
        // Lượt đồng bộ có thể đã xóa dòng này (nguyên liệu không còn trong thực đơn và dòng chưa
        // được đánh dấu gì), nên đọc lại từ chính danh sách vừa đồng bộ thay vì dùng bản cũ.
        row = synced.list().getItems().stream()
                .filter(saved -> saved.getId().equals(itemId))
                .findFirst()
                .orElseThrow(ShoppingListService::itemNotFound);

        applyPatch(row, request);
        lists.saveAndFlush(synced.list());

        return render(synced, Set.of());
    }

    /**
     * Xóa một dòng <strong>tự nhập</strong>.
     *
     * <p>Dòng nguyên liệu không xóa được: nó là hệ quả của thực đơn, nên cách bỏ nó khỏi danh sách
     * là bỏ chọn món hoặc đổi món — xóa dòng sẽ bị chính lượt đồng bộ kế tiếp tạo lại, và một nút
     * xóa không xóa được gì là một lời hứa sai.</p>
     */
    @Transactional
    public ShoppingListView deleteCustomLine(String userId, String itemId) {
        ShoppingListItemEntity row = requireRow(userId, itemId);
        if (!row.isCustom()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Chỉ xóa được dòng bạn tự thêm. Dòng nguyên liệu đến từ thực đơn — hãy bỏ chọn "
                            + "món hoặc đổi món.",
                    Map.of("item_id", "không phải dòng tự nhập"));
        }
        ShoppingListEntity list = row.getList();
        list.getItems().removeIf(saved -> saved.getId().equals(itemId));
        lists.saveAndFlush(list);

        Synced synced = loadAndSync(userId, list.getPlanId(), list.getStartDate(),
                list.getEndDate());
        return render(synced, Set.of());
    }

    // =========================================================================================
    // Đồng bộ
    // =========================================================================================

    /** Kế hoạch, danh sách đã khớp với kế hoạch, và các dòng dẫn xuất của phạm vi ngày này. */
    private record Synced(MealPlanEntity plan, ShoppingListEntity list,
                          List<AggregatedLine> lines) {
    }

    private Synced loadAndSync(String userId, String planId, LocalDate from, LocalDate to) {
        MealPlanEntity plan = plans.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                        "Không tìm thấy thực đơn này."));
        LocalDate start = from == null ? plan.getStartDate() : from;
        LocalDate end = to == null ? plan.getEndDate() : to;
        requireRangeInsidePlan(plan, start, end);

        List<AggregatedLine> lines = aggregator.aggregate(portionsOf(plan, start, end));
        ShoppingListEntity list = lists
                .findByPlanIdAndUserIdAndStartDateAndEndDate(planId, userId, start, end)
                .orElseGet(() -> newList(userId, plan, start, end));

        if (list.getId() == null || list.getPlanVersion() != plan.getPlanVersion()) {
            sync(list, lines);
            list.setPlanVersion(plan.getPlanVersion());
            lists.saveAndFlush(list);
        }
        return new Synced(plan, list, lines);
    }

    private ShoppingListEntity newList(String userId, MealPlanEntity plan, LocalDate start,
                                       LocalDate end) {
        ShoppingListEntity list = new ShoppingListEntity();
        list.setUserId(userId);
        list.setPlanId(plan.getId());
        list.setStartDate(start);
        list.setEndDate(end);
        // plan_version để 0 ở đây là cố ý: nó khác mọi plan_version hợp lệ (>= 1), nên nhánh đồng
        // bộ bên trên luôn chạy cho một danh sách vừa dựng.
        list.setPlanVersion(0);
        return list;
    }

    /**
     * Viết lại <strong>lượng cần</strong> của các dòng, giữ nguyên thao tác của người dùng.
     *
     * <p>Ba trường hợp, và sự khác nhau giữa chúng chính là nội dung của TC22:</p>
     * <ul>
     *   <li>nguyên liệu vẫn còn trong thực đơn → dòng cũ ở nguyên chỗ, chỉ {@code quantity} được
     *       viết lại. Trạng thái đã có / đã mua <strong>không bị chạm tới</strong>;</li>
     *   <li>nguyên liệu mới xuất hiện → thêm dòng mới ở trạng thái chưa mua;</li>
     *   <li>nguyên liệu không còn trong thực đơn → dòng chưa đánh dấu gì thì xóa, còn dòng
     *       <em>đã có dấu</em> thì giữ lại với lượng cần 0 kèm ghi chú. Xóa im lặng một dòng người
     *       ta đã mua là vứt đi một thông tin có thật.</li>
     * </ul>
     */
    private void sync(ShoppingListEntity list, List<AggregatedLine> lines) {
        Map<String, AggregatedLine> byKey = new LinkedHashMap<>();
        for (AggregatedLine line : lines) {
            byKey.put(keyOf(line.ingredientId(), line.foodState()), line);
        }

        Map<String, ShoppingListItemEntity> existing = new LinkedHashMap<>();
        for (ShoppingListItemEntity row : list.getItems()) {
            if (!row.isCustom()) {
                existing.put(keyOf(row.getIngredientId(), row.getFoodState()), row);
            }
        }

        for (var entry : byKey.entrySet()) {
            AggregatedLine line = entry.getValue();
            ShoppingListItemEntity row = existing.get(entry.getKey());
            if (row == null) {
                row = new ShoppingListItemEntity();
                row.setIngredientId(line.ingredientId());
                row.setFoodState(line.foodState());
                row.setState(ShoppingItemState.NEEDED);
                list.add(row);
            }
            row.setDisplayName(line.name());
            row.setFoodGroup(line.foodGroup());
            row.setUnit(MassUnits.GRAM);
            row.setQuantity(scaled(line.quantityGram(), QUANTITY_SCALE));
        }

        list.getItems().removeIf(row -> !row.isCustom()
                && !byKey.containsKey(keyOf(row.getIngredientId(), row.getFoodState()))
                && !marked(row));
        for (ShoppingListItemEntity row : list.getItems()) {
            if (!row.isCustom() && !byKey.containsKey(keyOf(row.getIngredientId(),
                    row.getFoodState()))) {
                row.setQuantity(BigDecimal.ZERO.setScale(QUANTITY_SCALE));
            }
        }
    }

    /** Dòng đã mang dấu vết của người dùng — thứ không lượt đồng bộ nào được phép xóa. */
    private boolean marked(ShoppingListItemEntity row) {
        return row.getState() != ShoppingItemState.NEEDED
                || positive(row.getHaveQuantity()) || positive(row.getPurchasedQuantity());
    }

    private List<PlannedPortion> portionsOf(MealPlanEntity plan, LocalDate start, LocalDate end) {
        List<PlannedPortion> portions = new ArrayList<>();
        for (MealSlotEntity slot : MealPlanService.orderedSlots(plan)) {
            if (slot.getLocalDate().isBefore(start) || slot.getLocalDate().isAfter(end)) {
                continue;
            }
            for (MealPlanItemEntity item : slot.getItems()) {
                RecipeSnapshot snapshot = json.readValue(item.getNutrientSnapshot(),
                        RecipeSnapshot.class);
                portions.add(new PlannedPortion(item.getId(), snapshot, item.getServings()));
            }
        }
        return portions;
    }

    // =========================================================================================
    // Áp thay đổi của người dùng lên một dòng
    // =========================================================================================

    private void applyPatch(ShoppingListItemEntity row, PatchShoppingItemRequest request) {
        if (request.customText() != null) {
            if (!row.isCustom()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Chỉ dòng bạn tự thêm mới sửa được nội dung.",
                        Map.of("custom_text", "dòng này đến từ thực đơn"));
            }
            row.setCustomText(request.customText().trim());
        }

        BigDecimal have = convert(request.haveQuantity(), request.unit(), row, "have_quantity");
        BigDecimal purchased =
                convert(request.purchasedQuantity(), request.unit(), row, "purchased_quantity");
        if (have != null) {
            row.setHaveQuantity(have);
        }
        if (purchased != null) {
            row.setPurchasedQuantity(purchased);
        }

        if (request.state() != null) {
            applyState(row, request.state(), have, purchased);
        }
    }

    /**
     * Trạng thái mới, kèm con số đi theo nó khi người dùng không tự nhập.
     *
     * <p>Tick "đã mua" mà không gõ số thì con số là <strong>toàn bộ lượng cần</strong> — đó là
     * điều người ta vừa làm ở chợ. Bỏ tick về "chưa mua" thì xóa cả hai con số: giữ lại "đã mua
     * 200 g" trên một dòng đang nói "chưa mua gì" là hai câu mâu thuẫn nằm cạnh nhau, và lần đọc
     * sau không ai biết câu nào đúng.</p>
     */
    private void applyState(ShoppingListItemEntity row, ShoppingItemState state,
                            BigDecimal sentHave, BigDecimal sentPurchased) {
        row.setState(state);
        switch (state) {
            case NEEDED -> {
                row.setHaveQuantity(null);
                row.setPurchasedQuantity(null);
            }
            case HAVE -> {
                if (sentHave == null && !positive(row.getHaveQuantity())) {
                    row.setHaveQuantity(row.getQuantity());
                }
            }
            case PURCHASED -> {
                if (sentPurchased == null && !positive(row.getPurchasedQuantity())) {
                    row.setPurchasedQuantity(remainingOf(row));
                }
            }
            default -> throw new IllegalStateException("Trạng thái chưa xử lý: " + state);
        }
    }

    /**
     * Quy một lượng người dùng nhập về đơn vị của dòng.
     *
     * <p><strong>Chỉ hỗ trợ đơn vị đã quy đổi được.</strong> Gửi "thìa" bị từ chối kèm tên trường,
     * không bị đoán thành một số gram — một con số đoán trong danh sách đi chợ trông hệt như một
     * con số đã biết, và người dùng không có cách nào phân biệt.</p>
     */
    private BigDecimal convert(BigDecimal quantity, String unit, ShoppingListItemEntity row,
                               String field) {
        if (quantity == null) {
            return null;
        }
        String target = row.getUnit();
        if (unit == null || unit.isBlank() || unit.trim().equalsIgnoreCase(target)) {
            return scaled(quantity, QUANTITY_SCALE);
        }
        if (row.isCustom()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Dòng tự nhập dùng đơn vị \"%s\", hãy nhập theo đúng đơn vị đó."
                            .formatted(target == null ? "" : target),
                    Map.of(field, "đơn vị không khớp đơn vị của dòng"));
        }
        BigDecimal gram = MassUnits.toGram(quantity, unit)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "Chưa có quy đổi từ \"%s\" sang %s. Hãy nhập theo %s, hoặc chọn một trong: %s."
                                .formatted(unit.trim(), MassUnits.GRAM, MassUnits.GRAM,
                                        String.join(", ",
                                                new java.util.TreeSet<>(MassUnits.convertible()))),
                        Map.of(field, "đơn vị \"%s\" chưa quy đổi được".formatted(unit.trim()))));
        return scaled(gram, QUANTITY_SCALE);
    }

    // =========================================================================================
    // Dựng phản hồi
    // =========================================================================================

    private ShoppingListView render(Synced synced, Set<String> excludedItemIds) {
        Set<String> excluded = excludedItemIds == null ? Set.of() : excludedItemIds;
        Map<String, AggregatedLine> linesByKey = new LinkedHashMap<>();
        for (AggregatedLine line : synced.lines()) {
            linesByKey.put(keyOf(line.ingredientId(), line.foodState()), line);
        }

        List<ShoppingListItemEntity> rows = new ArrayList<>(synced.list().getItems());
        rows.sort(rowOrdering(linesByKey));

        Map<String, IngredientEntity> catalog = catalogOf(rows);
        Map<String, PriceEstimateEntity> priceList = priceListOf(rows);

        List<Rendered> rendered = new ArrayList<>();
        for (ShoppingListItemEntity row : rows) {
            Rendered item = renderRow(row, linesByKey, catalog, priceList, excluded);
            if (item != null) {
                rendered.add(item);
            }
        }

        return listView(synced, rendered);
    }

    /** Một dòng đã dựng xong, kèm hai con số mà mức danh sách còn phải cộng lại. */
    private record Rendered(ShoppingItemView view, FoodGroup group, boolean custom,
                            BigDecimal cost, BigDecimal remainingCost, boolean priceMissing) {
    }

    private Rendered renderRow(ShoppingListItemEntity row, Map<String, AggregatedLine> linesByKey,
                               Map<String, IngredientEntity> catalog,
                               Map<String, PriceEstimateEntity> priceList, Set<String> excluded) {
        if (row.isCustom()) {
            return renderCustomRow(row);
        }

        AggregatedLine line = linesByKey.get(keyOf(row.getIngredientId(), row.getFoodState()));
        List<Usage> usages = line == null ? List.of() : line.usages().stream()
                .filter(usage -> !excluded.contains(usage.itemId()))
                .toList();
        if (line != null && usages.isEmpty()) {
            // Mọi món dùng nguyên liệu này đều bị bỏ chọn ở lần xem này — dòng không hiện ra, và
            // KHÔNG bị xóa: bỏ chọn là lựa chọn của lần xem, không phải một thay đổi của thực đơn.
            return null;
        }

        BigDecimal quantity = line == null ? row.getQuantity()
                : scaled(sumOf(usages), QUANTITY_SCALE);
        BigDecimal remaining = remaining(quantity, row);
        Optional<BigDecimal> cost = cost(quantity, priceList.get(row.getIngredientId()));
        Optional<BigDecimal> remainingCost =
                cost(remaining, priceList.get(row.getIngredientId()));

        List<ReasonNote> notes = new ArrayList<>();
        if (line == null) {
            notes.add(ReasonNote.of(RecommendationReason.SHOPPING_ITEM_NO_LONGER_NEEDED,
                    row.getDisplayName()));
        } else if (row.getState() != ShoppingItemState.NEEDED && positive(remaining)) {
            notes.add(ReasonNote.of(RecommendationReason.SHOPPING_NEED_MORE,
                    amount(remaining, row.getUnit())));
        }
        if (cost.isEmpty()) {
            notes.add(ReasonNote.of(RecommendationReason.SHOPPING_PRICE_UNAVAILABLE,
                    row.getDisplayName()));
        }
        labelReminder(catalog.get(row.getIngredientId())).ifPresent(notes::add);

        ShoppingItemView view = new ShoppingItemView(row.getId(), row.getIngredientId(),
                row.getDisplayName(), row.getFoodState(), row.getFoodGroup(), row.getState(),
                quantity, row.getUnit(), row.getHaveQuantity(), row.getPurchasedQuantity(),
                remaining, cost.orElse(null), null, usageViews(usages),
                MealPlanService.views(notes));
        return new Rendered(view, row.getFoodGroup(), false, cost.orElse(null),
                remainingCost.orElse(null), cost.isEmpty());
    }

    /**
     * Một dòng tự nhập.
     *
     * <p>Không nguyên liệu, không giá, không "món sử dụng" — và ghi chú nói thẳng rằng nó không
     * tính vào dinh dưỡng, để người đọc không chờ đợi điều ngược lại. Nó cũng không làm tổng chi
     * phí thành "chưa đầy đủ": dòng này vốn không thuộc thực đơn nên chưa bao giờ hứa có giá.</p>
     */
    private Rendered renderCustomRow(ShoppingListItemEntity row) {
        ShoppingItemView view = new ShoppingItemView(row.getId(), null, row.getCustomText(),
                null, null, row.getState(), row.getQuantity(), row.getUnit(),
                row.getHaveQuantity(), row.getPurchasedQuantity(),
                remaining(row.getQuantity(), row), null, row.getCustomText(), List.of(),
                MealPlanService.views(
                        List.of(ReasonNote.of(RecommendationReason.SHOPPING_CUSTOM_LINE))));
        return new Rendered(view, null, true, null, null, false);
    }

    private ShoppingListView listView(Synced synced, List<Rendered> rendered) {
        List<ShoppingGroupView> groups = new ArrayList<>();
        Map<FoodGroup, List<ShoppingItemView>> byGroup = new LinkedHashMap<>();
        List<ShoppingItemView> custom = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal remainingTotal = BigDecimal.ZERO;
        int withoutPrice = 0;

        for (Rendered item : rendered) {
            if (item.custom()) {
                custom.add(item.view());
                continue;
            }
            byGroup.computeIfAbsent(item.group(), missing -> new ArrayList<>()).add(item.view());
            if (item.priceMissing()) {
                withoutPrice++;
                continue;
            }
            total = total.add(item.cost());
            remainingTotal = remainingTotal.add(
                    item.remainingCost() == null ? BigDecimal.ZERO : item.remainingCost());
        }

        byGroup.forEach((group, items) -> groups.add(new ShoppingGroupView(group, items)));
        if (!custom.isEmpty()) {
            // Nhóm không có food_group, luôn xếp cuối: dòng tự nhập không thuộc nhóm thực phẩm nào
            // vì nó không phải nguyên liệu của thực đơn.
            groups.add(new ShoppingGroupView(null, List.copyOf(custom)));
        }

        List<ReasonNote> notes = new ArrayList<>();
        if (withoutPrice > 0) {
            notes.add(ReasonNote.of(RecommendationReason.SHOPPING_COST_INCOMPLETE,
                    String.valueOf(withoutPrice)));
        }

        return new ShoppingListView(synced.list().getId(), synced.plan().getId(),
                synced.list().getPlanVersion(), synced.list().getStartDate(),
                synced.list().getEndDate(), List.copyOf(groups),
                scaled(total, MONEY_SCALE), scaled(remainingTotal, MONEY_SCALE),
                CoverageStatus.of(withoutPrice == 0), MealPlanService.views(notes));
    }

    /**
     * Nhắc đọc nhãn thành phần ở mức nguyên liệu.
     *
     * <p>Bài học từ Mealime: lọc công thức theo dị ứng <strong>không thay thế</strong> việc đọc
     * nhãn khi mua gia vị hoặc thực phẩm đóng gói. Nhắc ở đúng hai nhóm nguyên liệu mà cái nhãn
     * mới là nơi kiểm được:</p>
     * <ul>
     *   <li>nguyên liệu <em>chưa được rà</em> thành phần gây dị ứng ({@link AllergenStatus#UNKNOWN});</li>
     *   <li>nguyên liệu <em>hỗn hợp</em> — nước mắm, nước dùng, gói gia vị nêm sẵn — vì thứ gây dị
     *       ứng nằm trong nguyên liệu con, và nhãn của hãng này khác hãng kia.</li>
     * </ul>
     *
     * <p>Chỉ nhắc, không chặn: món đã qua bước lọc rồi, đây là lời dặn lúc đứng ở quầy.</p>
     */
    private Optional<ReasonNote> labelReminder(IngredientEntity ingredient) {
        if (ingredient == null) {
            return Optional.empty();
        }
        boolean packaged = ingredient.getFoodGroup() == FoodGroup.SEASONING
                || ingredient.getFoodState() == FoodState.PROCESSED;
        boolean uncertain = ingredient.getAllergenStatus() == AllergenStatus.UNKNOWN
                || !ingredient.getComponentIngredientIds().isEmpty();
        if (!packaged || !uncertain) {
            return Optional.empty();
        }
        return Optional.of(ReasonNote.of(RecommendationReason.SHOPPING_READ_PRODUCT_LABEL,
                ingredient.getCanonicalName()));
    }

    // =========================================================================================
    // Hạ tầng chung
    // =========================================================================================

    private Comparator<ShoppingListItemEntity> rowOrdering(
            Map<String, AggregatedLine> linesByKey) {
        return Comparator
                // Dòng tự nhập luôn xuống cuối.
                .comparing(ShoppingListItemEntity::isCustom)
                .thenComparingInt(row -> row.getFoodGroup() == null
                        ? Integer.MAX_VALUE : row.getFoodGroup().ordinal())
                // Dòng không còn trong thực đơn xếp sau các dòng còn cần của cùng nhóm.
                .thenComparing(row -> row.isCustom() || linesByKey.containsKey(
                        keyOf(row.getIngredientId(), row.getFoodState())) ? 0 : 1)
                .thenComparing(row -> displayNameOf(row), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> row.getFoodState() == null ? "" : row.getFoodState().name());
    }

    private static String displayNameOf(ShoppingListItemEntity row) {
        if (row.getDisplayName() != null) {
            return row.getDisplayName();
        }
        return row.getCustomText() == null ? "" : row.getCustomText();
    }

    private Map<String, IngredientEntity> catalogOf(List<ShoppingListItemEntity> rows) {
        List<String> ids = rows.stream()
                .map(ShoppingListItemEntity::getIngredientId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return ingredients.findAllById(ids).stream()
                .collect(Collectors.toMap(IngredientEntity::getId, ingredient -> ingredient));
    }

    private Map<String, PriceEstimateEntity> priceListOf(List<ShoppingListItemEntity> rows) {
        List<String> ids = rows.stream()
                .map(ShoppingListItemEntity::getIngredientId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return prices.findByIngredientIdIn(ids).stream()
                .collect(Collectors.toMap(PriceEstimateEntity::getIngredientId, price -> price));
    }

    /**
     * Chi phí của một lượng gram, hoặc rỗng khi <strong>chưa quy đổi được giá</strong>.
     *
     * <p>Rỗng ở đây có nghĩa "không ai biết dòng này bao nhiêu tiền" — vì nguyên liệu chưa có dòng
     * giá, hoặc vì giá ghi theo đơn vị chưa quy đổi được ("lít", "quả"). Nó <strong>không</strong>
     * có nghĩa là 0 đồng, nên nơi gọi phải nói ra chứ không cộng một số 0 vào tổng.</p>
     */
    private Optional<BigDecimal> cost(BigDecimal quantityGram, PriceEstimateEntity price) {
        if (quantityGram == null || price == null || price.getPricePerBaseUnitVnd() == null) {
            return Optional.empty();
        }
        return MassUnits.fromGram(quantityGram, price.getBaseUnit())
                .map(units -> units.multiply(price.getPricePerBaseUnitVnd())
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }

    private List<ShoppingUsageView> usageViews(List<Usage> usages) {
        List<ShoppingUsageView> views = new ArrayList<>();
        for (Usage usage : usages) {
            views.add(new ShoppingUsageView(usage.itemId(), usage.recipeId(), usage.recipeName(),
                    scaled(usage.quantityGram(), QUANTITY_SCALE)));
        }
        return List.copyOf(views);
    }

    private ShoppingListItemEntity requireRow(String userId, String itemId) {
        return listItems.findByIdAndListUserId(itemId, userId)
                .orElseThrow(ShoppingListService::itemNotFound);
    }

    private void requireRangeInsidePlan(MealPlanEntity plan, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "end_date phải từ start_date trở đi.",
                    Map.of("end_date", "trước start_date"));
        }
        if (start.isBefore(plan.getStartDate()) || end.isAfter(plan.getEndDate())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Phạm vi ngày phải nằm trong thực đơn (%s đến %s)."
                            .formatted(plan.getStartDate(), plan.getEndDate()),
                    Map.of("start_date", "ngoài phạm vi của thực đơn"));
        }
    }

    private BigDecimal remaining(BigDecimal quantity, ShoppingListItemEntity row) {
        if (quantity == null) {
            return null;
        }
        BigDecimal covered = zeroIfNull(row.getHaveQuantity())
                .add(zeroIfNull(row.getPurchasedQuantity()));
        BigDecimal remaining = quantity.subtract(covered);
        return remaining.signum() < 0 ? BigDecimal.ZERO.setScale(QUANTITY_SCALE) : remaining;
    }

    private BigDecimal remainingOf(ShoppingListItemEntity row) {
        return remaining(row.getQuantity(), row);
    }

    private static String keyOf(String ingredientId, FoodState state) {
        return ingredientId + "#" + (state == null ? "" : state.name());
    }

    private static BigDecimal sumOf(List<Usage> usages) {
        return usages.stream().map(Usage::quantityGram)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String amount(BigDecimal quantity, String unit) {
        return quantity.stripTrailingZeros().toPlainString() + (unit == null ? "" : " " + unit);
    }

    private static BigDecimal scaled(BigDecimal value, int scale) {
        return value == null ? null : value.setScale(scale, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BusinessException itemNotFound() {
        return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND,
                "Không tìm thấy dòng này trong danh sách mua sắm.");
    }
}
