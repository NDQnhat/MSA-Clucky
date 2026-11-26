package com.clucky.catalogstoreservice.service;

import com.clucky.catalogstoreservice.config.LocaleResolverHelper;
import com.clucky.catalogstoreservice.dto.LocalizedText;
import com.clucky.catalogstoreservice.dto.ValidationDto;
import com.clucky.catalogstoreservice.entity.*;
import com.clucky.catalogstoreservice.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ValidationService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final StoreRepository storeRepository;
    private final ProductRepository productRepository;
    private final ProductOptionRepository optionRepository;
    private final ProductToppingRepository toppingRepository;
    private final ComboRepository comboRepository;
    private final StoreProductRepository storeProductRepository;
    private final StoreInventoryRepository storeInventoryRepository;
    private final StoreService storeService;

    public ValidationService(StoreRepository storeRepository,
                             ProductRepository productRepository,
                             ProductOptionRepository optionRepository,
                             ProductToppingRepository toppingRepository,
                             ComboRepository comboRepository,
                             StoreProductRepository storeProductRepository,
                             StoreInventoryRepository storeInventoryRepository,
                             StoreService storeService) {
        this.storeRepository = storeRepository;
        this.productRepository = productRepository;
        this.optionRepository = optionRepository;
        this.toppingRepository = toppingRepository;
        this.comboRepository = comboRepository;
        this.storeProductRepository = storeProductRepository;
        this.storeInventoryRepository = storeInventoryRepository;
        this.storeService = storeService;
    }

    public ValidationDto.Response validateOrderItems(ValidationDto.Request request) {
        return validateOrderItems(request, LocaleResolverHelper.currentRequestLang());
    }

    public ValidationDto.Response validateOrderItems(ValidationDto.Request request, String lang) {
        boolean isEn = "en".equalsIgnoreCase(lang);
        ValidationDto.Response response = new ValidationDto.Response();
        List<ValidationDto.ValidatedItem> resultItems = new ArrayList<>();
        BigDecimal totalSubtotal = BigDecimal.ZERO;
        boolean allValid = true;

        if (request.getStoreId() == null) {
            response.setValid(false);
            response.setMessage(isEn ? "storeId is required." : "storeId không được để trống!");
            return response;
        }

        Optional<Store> storeOpt = storeRepository.findById(request.getStoreId());
        if (storeOpt.isEmpty()) {
            response.setValid(false);
            response.setStoreOpen(false);
            response.setMessage(isEn
                    ? "Store not found: " + request.getStoreId()
                    : "Không tìm thấy chi nhánh với mã: " + request.getStoreId());
            return response;
        }

        Store store = storeOpt.get();
        String storeName = store.resolveName(lang);
        if (!storeService.isStoreServingNow(store)) {
            response.setStoreOpen(false);
            response.setValid(false);
            String message;
            if (store.getStatus() == StoreStatus.INACTIVE) {
                message = isEn
                        ? "Store '" + storeName + "' is currently inactive."
                        : "Chi nhánh '" + storeName + "' hiện đã ngừng kinh doanh.";
            } else if (store.getStatus() == StoreStatus.CLOSED_TEMP) {
                message = isEn
                        ? "Store '" + storeName + "' is temporarily closed."
                        : "Chi nhánh '" + storeName + "' hiện đang tạm đóng cửa.";
            } else {
                String openStr = store.getOpenTime() != null ? store.getOpenTime().format(TIME_FORMAT) : "";
                String closeStr = store.getCloseTime() != null ? store.getCloseTime().format(TIME_FORMAT) : "";
                message = isEn
                        ? "Store '" + storeName + "' is outside operating hours (Open hours: " + openStr + " - " + closeStr + "). Please return during service hours."
                        : "Chi nhánh '" + storeName + "' hiện đang ngoài giờ phục vụ (Giờ mở cửa: " + openStr + " - " + closeStr + "). Vui lòng quay lại trong giờ hoạt động.";
            }
            response.setMessage(message);
        }

        if (request.getItems() == null || request.getItems().isEmpty()) {
            response.setValid(false);
            response.setMessage(isEn ? "Items list cannot be empty." : "Danh sách món kiểm tra không được để trống!");
            return response;
        }

        Map<Long, Long> totalDemandedQuantity = new HashMap<>();
        Map<Long, Combo> comboCache = new HashMap<>();

        for (ValidationDto.ValidateItemRequest itemReq : request.getItems()) {
            int qty = itemReq.getQuantity() != null && itemReq.getQuantity() > 0 ? itemReq.getQuantity() : 1;
            if (itemReq.getProductId() != null) {
                totalDemandedQuantity.merge(itemReq.getProductId(), (long) qty, (current, added) -> current > Long.MAX_VALUE - added ? Long.MAX_VALUE : current + added);
            } else if (itemReq.getComboId() != null) {
                Combo combo = comboCache.computeIfAbsent(itemReq.getComboId(), id ->
                        comboRepository.findByIdWithItems(id).orElse(null)
                );
                if (combo != null && combo.getItems() != null) {
                    for (ComboItem ci : combo.getItems()) {
                        if (ci.getProduct() != null && ci.getProduct().getId() != null) {
                            long compQty = (long) (ci.getQuantity() != null && ci.getQuantity() > 0 ? ci.getQuantity() : 1) * qty;
                            totalDemandedQuantity.merge(ci.getProduct().getId(), compQty, (current, added) -> current > Long.MAX_VALUE - added ? Long.MAX_VALUE : current + added);
                        }
                    }
                }
            }
        }

        for (ValidationDto.ValidateItemRequest itemReq : request.getItems()) {
            ValidationDto.ValidatedItem itemResult = new ValidationDto.ValidatedItem();
            int qty = itemReq.getQuantity() != null && itemReq.getQuantity() > 0 ? itemReq.getQuantity() : 1;
            itemResult.setQuantity(qty);

            if (itemReq.getProductId() != null) {
                itemResult.setProductId(itemReq.getProductId());
                if (itemReq.getComboSelections() != null && !itemReq.getComboSelections().isEmpty()) {
                    itemResult.setAvailable(false);
                    itemResult.setErrorCode("VALIDATION_ERROR");
                    itemResult.setReason(isEn ? "Combo selections are only valid for combo items." : "Tùy chọn combo chỉ được gửi cho món thuộc combo.");
                    allValid = false;
                }
                Optional<Product> prodOpt = productRepository.findById(itemReq.getProductId());
                if (prodOpt.isEmpty() || !Boolean.TRUE.equals(prodOpt.get().getIsActive())) {
                    itemResult.setAvailable(false);
                    itemResult.setErrorCode("RESOURCE_NOT_FOUND");
                    itemResult.setReason(isEn ? "Item does not exist or is no longer active." : "Món ăn không tồn tại hoặc đã ngừng phục vụ.");
                    allValid = false;
                } else {
                    Product prod = prodOpt.get();
                    itemResult.setName(prod.resolveName(lang));
                    itemResult.setNameI18n(prod.getName());

                    Optional<StoreProduct> spOpt = storeProductRepository.findByStoreIdAndProductId(store.getId(), prod.getId());
                    Optional<StoreInventory> siOpt = storeInventoryRepository.findByStoreIdAndProductId(store.getId(), prod.getId());

                    if (spOpt.isEmpty()) {
                        itemResult.setAvailable(false);
                        itemResult.setErrorCode("RESOURCE_NOT_FOUND");
                        itemResult.setReason(isEn ? "Item is not available at this store." : "Món ăn không khả dụng tại chi nhánh này.");
                        allValid = false;
                    } else if (!Boolean.TRUE.equals(spOpt.get().getIsAvailable())) {
                        itemResult.setAvailable(false);
                        itemResult.setErrorCode("PRODUCT_OUT_OF_STOCK");
                        itemResult.setReason(isEn ? "Item is currently disabled at this store." : "Món ăn hiện không được bán tại chi nhánh này.");
                        allValid = false;
                    } else if (Boolean.TRUE.equals(spOpt.get().getIsSoldOut())) {
                        itemResult.setAvailable(false);
                        itemResult.setInsufficientStock(true);
                        itemResult.setErrorCode("PRODUCT_OUT_OF_STOCK");
                        itemResult.setReason(isEn ? "Item is sold out at this store." : "Món ăn đã hết hàng tại chi nhánh này.");
                        allValid = false;
                    } else if (siOpt.isEmpty()) {
                        itemResult.setAvailable(false);
                        itemResult.setInsufficientStock(true);
                        itemResult.setErrorCode("INSUFFICIENT_INVENTORY");
                        itemResult.setReason(isEn ? "Item has no inventory record at this store." : "Món ăn không có dữ liệu tồn kho tại chi nhánh này.");
                        allValid = false;
                    } else {
                        siOpt.ifPresent(si -> itemResult.setInventoryVersion(si.getVersion()));
                        int availableStock = siOpt.get().getQuantityAvailable() != null ? siOpt.get().getQuantityAvailable() : 0;
                        long requiredStock = totalDemandedQuantity.getOrDefault(prod.getId(), (long) qty);
                        if (availableStock < requiredStock) {
                            itemResult.setAvailable(false);
                            itemResult.setInsufficientStock(true);
                            itemResult.setErrorCode("INSUFFICIENT_INVENTORY");
                            itemResult.setReason(isEn ? "Insufficient stock at this store." : "Món ăn không đủ số lượng tồn kho tại chi nhánh.");
                            allValid = false;
                        }
                    }

                    BigDecimal unitPrice = spOpt.map(StoreProduct::getStorePrice).filter(Objects::nonNull).orElse(prod.getBasePrice());
                    itemResult.setUnitPrice(unitPrice);

                    List<ProductOption> productOptions = optionRepository.findByProductId(prod.getId());
                    Map<LocalizedText, List<ProductOption>> groupMap = productOptions.stream()
                            .collect(Collectors.groupingBy(ProductOption::getGroupName, LinkedHashMap::new, Collectors.toList()));
                    Map<Long, ProductOption> validOptionMap = productOptions.stream()
                            .collect(Collectors.toMap(ProductOption::getId, o -> o));

                    BigDecimal optionExtras = BigDecimal.ZERO;
                    boolean optionsValid = true;
                    Set<LocalizedText> chosenGroups = new HashSet<>();
                    List<Long> requestedOptionIds = itemReq.getOptionIds() != null ? itemReq.getOptionIds() : Collections.emptyList();

                    for (Long optId : requestedOptionIds) {
                        ProductOption opt = validOptionMap.get(optId);
                        if (opt == null) {
                            itemResult.setAvailable(false);
                            itemResult.setErrorCode("VALIDATION_ERROR");
                            itemResult.setReason(isEn
                                    ? "Option (optionId=" + optId + ") is invalid or does not belong to this item."
                                    : "Tùy chọn (optionId=" + optId + ") không hợp lệ hoặc không thuộc món ăn này.");
                            allValid = false;
                            optionsValid = false;
                            break;
                        }
                        if (!chosenGroups.add(opt.getGroupName())) {
                            itemResult.setAvailable(false);
                            itemResult.setErrorCode("VALIDATION_ERROR");
                            itemResult.setReason(isEn
                                    ? "Cannot select more than 1 option for group '" + opt.resolveGroupName(lang) + "'."
                                    : "Không thể chọn nhiều hơn 1 tùy chọn cho nhóm '" + opt.resolveGroupName(lang) + "'.");
                            allValid = false;
                            optionsValid = false;
                            break;
                        }
                        optionExtras = optionExtras.add(opt.getExtraPrice());
                        itemResult.getOptionsSnapshot().add(new ValidationDto.OptionSnapshot(
                                opt.getId(), opt.resolveGroupName(lang), opt.resolveName(lang), opt.getExtraPrice(),
                                opt.getGroupName(), opt.getName()));
                    }

                    if (optionsValid) {
                        for (Map.Entry<LocalizedText, List<ProductOption>> entry : groupMap.entrySet()) {
                            LocalizedText groupKey = entry.getKey();
                            boolean isRequired = entry.getValue().stream()
                                    .anyMatch(opt -> opt.getIsRequired() == null || Boolean.TRUE.equals(opt.getIsRequired()));
                            if (isRequired && !chosenGroups.contains(groupKey)) {
                                itemResult.setAvailable(false);
                                itemResult.setErrorCode("VALIDATION_ERROR");
                                itemResult.setReason(isEn
                                        ? "Missing required selection for group '" + LocalizedText.resolve(groupKey, lang) + "'."
                                        : "Thiếu tùy chọn bắt buộc cho nhóm '" + LocalizedText.resolve(groupKey, lang) + "'.");
                                allValid = false;
                                optionsValid = false;
                                break;
                            }
                        }
                    }

                    BigDecimal toppingExtras = BigDecimal.ZERO;
                    boolean toppingsValid = true;
                    List<ProductTopping> productToppings = toppingRepository.findByProductId(prod.getId());
                    Map<Long, ProductTopping> validToppingMap = productToppings.stream()
                            .collect(Collectors.toMap(ProductTopping::getId, t -> t));
                    List<Long> requestedToppingIds = itemReq.getToppingIds() != null ? itemReq.getToppingIds() : Collections.emptyList();

                    for (Long topId : requestedToppingIds) {
                        ProductTopping top = validToppingMap.get(topId);
                        if (top == null) {
                            itemResult.setAvailable(false);
                            itemResult.setErrorCode("VALIDATION_ERROR");
                            itemResult.setReason(isEn
                                    ? "Topping (toppingId=" + topId + ") is invalid or does not belong to this item."
                                    : "Topping (toppingId=" + topId + ") không hợp lệ hoặc không thuộc món ăn này.");
                            allValid = false;
                            toppingsValid = false;
                            break;
                        }
                        toppingExtras = toppingExtras.add(top.getExtraPrice());
                        itemResult.getToppingsSnapshot().add(new ValidationDto.ToppingSnapshot(
                                top.getId(), top.resolveToppingName(lang), top.getExtraPrice(), top.getToppingName()));
                    }

                    if (optionsValid && toppingsValid && itemResult.isAvailable()) {
                        BigDecimal extras = optionExtras.add(toppingExtras);
                        itemResult.setExtraPrice(extras);
                        BigDecimal lineTotal = unitPrice.add(extras).multiply(BigDecimal.valueOf(qty));
                        itemResult.setLineTotal(lineTotal);
                        totalSubtotal = totalSubtotal.add(lineTotal);
                    } else {
                        itemResult.setExtraPrice(BigDecimal.ZERO);
                        itemResult.setLineTotal(BigDecimal.ZERO);
                    }
                }
            } else if (itemReq.getComboId() != null) {
                itemResult.setComboId(itemReq.getComboId());
                Combo combo = comboCache.computeIfAbsent(itemReq.getComboId(), id ->
                        comboRepository.findByIdWithItems(id).orElse(null)
                );

                if (combo == null || !Boolean.TRUE.equals(combo.getIsActive())) {
                    itemResult.setAvailable(false);
                    itemResult.setErrorCode("RESOURCE_NOT_FOUND");
                    itemResult.setReason(isEn ? "Combo does not exist or is no longer active." : "Combo không tồn tại hoặc đã ngừng áp dụng.");
                    allValid = false;
                } else {
                    OffsetDateTime now = OffsetDateTime.now();
                    if (combo.getStartAt() != null && now.isBefore(combo.getStartAt())) {
                        itemResult.setAvailable(false);
                        itemResult.setErrorCode("VALIDATION_ERROR");
                        itemResult.setReason(isEn ? "Combo is not active yet." : "Combo chưa đến thời gian áp dụng.");
                        allValid = false;
                    } else if (combo.getEndAt() != null && now.isAfter(combo.getEndAt())) {
                        itemResult.setAvailable(false);
                        itemResult.setErrorCode("VALIDATION_ERROR");
                        itemResult.setReason(isEn ? "Combo has expired." : "Combo đã hết thời gian áp dụng.");
                        allValid = false;
                    } else if (combo.getItems() == null || combo.getItems().isEmpty()) {
                        itemResult.setAvailable(false);
                        itemResult.setErrorCode("VALIDATION_ERROR");
                        itemResult.setReason(isEn ? "Combo has no items." : "Combo chưa có món thành phần.");
                        allValid = false;
                    } else {
                        for (ComboItem ci : combo.getItems()) {
                            Product cp = ci.getProduct();
                            if (cp == null || !Boolean.TRUE.equals(cp.getIsActive())) {
                                itemResult.setAvailable(false);
                                itemResult.setErrorCode("RESOURCE_NOT_FOUND");
                                String cpName = cp != null ? cp.resolveName(lang) : (isEn ? "component" : "thành phần");
                                itemResult.setReason(isEn
                                        ? "Combo component '" + cpName + "' is no longer active."
                                        : "Món '" + cpName + "' trong combo đã ngừng phục vụ.");
                                allValid = false;
                                break;
                            }

                            Optional<StoreProduct> spOpt = storeProductRepository.findByStoreIdAndProductId(store.getId(), cp.getId());
                            Optional<StoreInventory> siOpt = storeInventoryRepository.findByStoreIdAndProductId(store.getId(), cp.getId());

                            if (spOpt.isEmpty()) {
                                itemResult.setAvailable(false);
                                itemResult.setErrorCode("RESOURCE_NOT_FOUND");
                                itemResult.setReason(isEn
                                        ? "Combo item '" + cp.resolveName(lang) + "' is unavailable at this store."
                                        : "Món '" + cp.resolveName(lang) + "' trong combo không khả dụng tại chi nhánh này.");
                                allValid = false;
                                break;
                            }
                            if (!Boolean.TRUE.equals(spOpt.get().getIsAvailable())) {
                                itemResult.setAvailable(false);
                                itemResult.setErrorCode("PRODUCT_OUT_OF_STOCK");
                                itemResult.setReason(isEn
                                        ? "Combo item '" + cp.resolveName(lang) + "' is not on sale at this store."
                                        : "Món '" + cp.resolveName(lang) + "' trong combo không được bán tại chi nhánh này.");
                                allValid = false;
                                break;
                            }
                            if (Boolean.TRUE.equals(spOpt.get().getIsSoldOut())) {
                                itemResult.setAvailable(false);
                                itemResult.setInsufficientStock(true);
                                itemResult.setErrorCode("PRODUCT_OUT_OF_STOCK");
                                itemResult.setReason(isEn
                                        ? "Combo item '" + cp.resolveName(lang) + "' is sold out at this store."
                                        : "Món '" + cp.resolveName(lang) + "' trong combo đã hết hàng tại chi nhánh này.");
                                allValid = false;
                                break;
                            }
                            if (siOpt.isEmpty()) {
                                itemResult.setAvailable(false);
                                itemResult.setInsufficientStock(true);
                                itemResult.setErrorCode("INSUFFICIENT_INVENTORY");
                                itemResult.setReason(isEn
                                        ? "Combo item '" + cp.resolveName(lang) + "' has no inventory data at this store."
                                        : "Món '" + cp.resolveName(lang) + "' trong combo không có dữ liệu tồn kho tại chi nhánh này.");
                                allValid = false;
                                break;
                            }
                            int availableStock = siOpt.get().getQuantityAvailable() != null ? siOpt.get().getQuantityAvailable() : 0;
                            long requiredStock = totalDemandedQuantity.getOrDefault(cp.getId(), 0L);
                            if (availableStock < requiredStock) {
                                itemResult.setAvailable(false);
                                itemResult.setInsufficientStock(true);
                                itemResult.setErrorCode("INSUFFICIENT_INVENTORY");
                                itemResult.setReason(isEn
                                        ? "Combo item '" + cp.resolveName(lang) + "' has insufficient stock at this store."
                                        : "Món '" + cp.resolveName(lang) + "' trong combo không đủ tồn kho tại chi nhánh.");
                                allValid = false;
                                break;
                            }
                        }
                    }

                    boolean hasFlatSelections = (itemReq.getOptionIds() != null && !itemReq.getOptionIds().isEmpty())
                            || (itemReq.getToppingIds() != null && !itemReq.getToppingIds().isEmpty());
                    if (hasFlatSelections) {
                        itemResult.setAvailable(false);
                        itemResult.setErrorCode("VALIDATION_ERROR");
                        itemResult.setReason(isEn
                                ? "Combo selections must be submitted per comboItemId."
                                : "Tùy chọn của combo phải được gửi theo từng comboItemId.");
                        allValid = false;
                    } else {
                        ComboSelectionValidation selectionValidation = validateComboSelections(
                                combo, itemReq.getComboSelections(), itemResult, lang);
                        if (!selectionValidation.valid()) {
                            itemResult.setAvailable(false);
                            itemResult.setErrorCode("VALIDATION_ERROR");
                            itemResult.setReason(selectionValidation.reason());
                            allValid = false;
                        } else {
                            itemResult.setExtraPrice(selectionValidation.extraPrice());
                        }
                    }

                    itemResult.setName(combo.resolveName(lang));
                    itemResult.setNameI18n(combo.getName());
                    itemResult.setUnitPrice(combo.getComboPrice());
                    if (itemResult.isAvailable()) {
                        BigDecimal lineTotal = combo.getComboPrice().add(itemResult.getExtraPrice())
                                .multiply(BigDecimal.valueOf(qty));
                        itemResult.setLineTotal(lineTotal);
                        totalSubtotal = totalSubtotal.add(lineTotal);
                    } else {
                        itemResult.setExtraPrice(BigDecimal.ZERO);
                        itemResult.setLineTotal(BigDecimal.ZERO);
                    }
                }
            } else {
                itemResult.setAvailable(false);
                itemResult.setErrorCode("VALIDATION_ERROR");
                itemResult.setReason(isEn ? "Item request is missing both productId and comboId." : "Yêu cầu món thiếu cả productId và comboId.");
                allValid = false;
            }

            resultItems.add(itemResult);
        }

        response.setValid(allValid && response.isStoreOpen());
        response.setSubtotal(totalSubtotal);
        response.setItems(resultItems);
        response.setStockQuantities(totalDemandedQuantity);
        if (!response.isValid() && response.getMessage() == null) {
            String firstReason = resultItems.stream()
                    .filter(it -> !it.isAvailable() && it.getReason() != null && !it.getReason().isBlank())
                    .map(it -> (it.getName() != null ? "[" + it.getName() + "] " : "") + it.getReason())
                    .findFirst()
                    .orElse(null);
            response.setMessage(firstReason != null
                    ? firstReason
                    : (isEn ? "Some items in your cart are unavailable at the selected store."
                            : "Một số món trong giỏ hàng không khả dụng tại chi nhánh đã chọn."));
        } else if (response.isValid()) {
            response.setMessage(isEn ? "All items are valid and ready to serve." : "Toàn bộ món ăn hợp lệ và sẵn sàng phục vụ.");
        }

        return response;
    }

    private record ComboSelectionValidation(boolean valid, String reason, BigDecimal extraPrice) {}

    private ComboSelectionValidation validateComboSelections(
            Combo combo,
            List<ValidationDto.ComboSelectionRequest> requestedSelections,
            ValidationDto.ValidatedItem itemResult,
            String lang) {
        boolean isEn = "en".equalsIgnoreCase(lang);
        Map<Long, ComboItem> comboItemsById = new LinkedHashMap<>();
        for (ComboItem comboItem : combo.getItems()) {
            if (comboItem.getId() == null || comboItemsById.put(comboItem.getId(), comboItem) != null) {
                return new ComboSelectionValidation(false,
                        isEn ? "Combo contains an invalid component." : "Combo có thành phần không hợp lệ.", BigDecimal.ZERO);
            }
        }

        Map<Long, ValidationDto.ComboSelectionRequest> selectionsByItemId = new HashMap<>();
        if (requestedSelections != null) {
            for (ValidationDto.ComboSelectionRequest selection : requestedSelections) {
                if (selection == null || selection.comboItemId() == null
                        || !comboItemsById.containsKey(selection.comboItemId())) {
                    return new ComboSelectionValidation(false,
                            isEn ? "comboItemId does not belong to this combo." : "comboItemId không thuộc combo đang được đặt.", BigDecimal.ZERO);
                }
                if (selectionsByItemId.put(selection.comboItemId(), selection) != null) {
                    return new ComboSelectionValidation(false,
                            isEn ? "Duplicate selections submitted for the same combo item." : "Không thể gửi nhiều lựa chọn cho cùng một thành phần combo.", BigDecimal.ZERO);
                }
            }
        }

        BigDecimal totalExtraPrice = BigDecimal.ZERO;
        for (ComboItem comboItem : combo.getItems()) {
            Product product = comboItem.getProduct();
            if (product == null || product.getId() == null) {
                return new ComboSelectionValidation(false,
                        isEn ? "Invalid combo component item." : "Món thành phần của combo không hợp lệ.", BigDecimal.ZERO);
            }

            int componentQuantity = comboItem.getQuantity() != null && comboItem.getQuantity() > 0
                    ? comboItem.getQuantity() : 1;
            List<ProductOption> productOptions = optionRepository.findByProductId(product.getId());
            Map<Long, ProductOption> optionsById = productOptions.stream()
                    .collect(Collectors.toMap(ProductOption::getId, option -> option));
            Map<LocalizedText, List<ProductOption>> optionsByGroup = productOptions.stream()
                    .collect(Collectors.groupingBy(ProductOption::getGroupName, LinkedHashMap::new, Collectors.toList()));

            ProductOption fixedOption = null;
            if (comboItem.getOptionId() != null) {
                fixedOption = optionsById.get(comboItem.getOptionId());
                if (fixedOption == null) {
                    return new ComboSelectionValidation(false,
                            isEn ? "Fixed option for '" + product.resolveName(lang) + "' is no longer valid."
                                    : "Tùy chọn cố định của món '" + product.resolveName(lang) + "' không còn hợp lệ.", BigDecimal.ZERO);
                }
                itemResult.getComboOptionsSnapshot().add(new ValidationDto.ComboOptionSnapshot(
                        comboItem.getId(), product.getId(), product.resolveName(lang), fixedOption.getId(),
                        fixedOption.resolveGroupName(lang), fixedOption.resolveName(lang), BigDecimal.ZERO, BigDecimal.ZERO,
                        componentQuantity, true,
                        product.getName(), fixedOption.getGroupName(), fixedOption.getName()));
            }

            ValidationDto.ComboSelectionRequest selection = selectionsByItemId.get(comboItem.getId());
            List<Long> requestedOptionIds = selection != null && selection.optionIds() != null
                    ? selection.optionIds() : List.of();
            Set<Long> seenOptionIds = new HashSet<>();
            Set<LocalizedText> chosenGroups = new HashSet<>();
            if (fixedOption != null) chosenGroups.add(fixedOption.getGroupName());
            boolean hasDynamicOptionSelection = false;
            BigDecimal optionExtras = BigDecimal.ZERO;

            for (Long optionId : requestedOptionIds) {
                if (optionId == null || !seenOptionIds.add(optionId)) {
                    return new ComboSelectionValidation(false,
                            isEn ? "Combo option list contains null or duplicate IDs." : "Danh sách tùy chọn combo chứa mã trống hoặc bị lặp.", BigDecimal.ZERO);
                }
                ProductOption option = optionsById.get(optionId);
                if (option == null) {
                    return new ComboSelectionValidation(false,
                            isEn ? "Option (optionId=" + optionId + ") does not belong to '" + product.resolveName(lang) + "'."
                                    : "Tùy chọn (optionId=" + optionId + ") không thuộc món '" + product.resolveName(lang) + "'.", BigDecimal.ZERO);
                }
                if (fixedOption != null && Objects.equals(option.getGroupName(), fixedOption.getGroupName())) {
                    if (Objects.equals(option.getId(), fixedOption.getId())) {
                        continue;
                    }
                    return new ComboSelectionValidation(false,
                            isEn ? "Group '" + fixedOption.resolveGroupName(lang) + "' already has a fixed option in this combo."
                                    : "Nhóm '" + fixedOption.resolveGroupName(lang) + "' đã có lựa chọn cố định trong combo.", BigDecimal.ZERO);
                }
                if (!chosenGroups.add(option.getGroupName())) {
                    return new ComboSelectionValidation(false,
                            isEn ? "Cannot select more than 1 option for group '" + option.resolveGroupName(lang) + "'."
                                    : "Không thể chọn nhiều hơn 1 tùy chọn cho nhóm '" + option.resolveGroupName(lang) + "'.", BigDecimal.ZERO);
                }
                hasDynamicOptionSelection = true;
                BigDecimal optionExtra = option.getExtraPrice() != null ? option.getExtraPrice() : BigDecimal.ZERO;
                optionExtras = optionExtras.add(optionExtra);
                itemResult.getComboOptionsSnapshot().add(new ValidationDto.ComboOptionSnapshot(
                        comboItem.getId(), product.getId(), product.resolveName(lang), option.getId(),
                        option.resolveGroupName(lang), option.resolveName(lang),
                        optionExtra.multiply(BigDecimal.valueOf(componentQuantity)), optionExtra,
                        componentQuantity, false,
                        product.getName(), option.getGroupName(), option.getName()));
            }

            if (hasDynamicOptionSelection) {
                for (Map.Entry<LocalizedText, List<ProductOption>> group : optionsByGroup.entrySet()) {
                    boolean required = group.getValue().stream()
                            .anyMatch(option -> option.getIsRequired() == null || Boolean.TRUE.equals(option.getIsRequired()));
                    if (required && !chosenGroups.contains(group.getKey())) {
                        return new ComboSelectionValidation(false,
                                isEn ? "Missing required option for '" + product.resolveName(lang) + "', group '" + LocalizedText.resolve(group.getKey(), lang) + "'."
                                        : "Thiếu tùy chọn bắt buộc cho món '" + product.resolveName(lang) + "', nhóm '" + LocalizedText.resolve(group.getKey(), lang) + "'.",
                                BigDecimal.ZERO);
                    }
                }
            }

            List<Long> requestedToppingIds = selection != null && selection.toppingIds() != null
                    ? selection.toppingIds() : List.of();
            Set<Long> seenToppingIds = new HashSet<>();
            Map<Long, ProductTopping> toppingsById = toppingRepository.findByProductId(product.getId()).stream()
                    .collect(Collectors.toMap(ProductTopping::getId, topping -> topping));
            BigDecimal toppingExtras = BigDecimal.ZERO;
            for (Long toppingId : requestedToppingIds) {
                if (toppingId == null || !seenToppingIds.add(toppingId)) {
                    return new ComboSelectionValidation(false,
                            isEn ? "Combo topping list contains null or duplicate IDs." : "Danh sách topping combo chứa mã trống hoặc bị lặp.", BigDecimal.ZERO);
                }
                ProductTopping topping = toppingsById.get(toppingId);
                if (topping == null) {
                    return new ComboSelectionValidation(false,
                            isEn ? "Topping (toppingId=" + toppingId + ") does not belong to '" + product.resolveName(lang) + "'."
                                    : "Topping (toppingId=" + toppingId + ") không thuộc món '" + product.resolveName(lang) + "'.", BigDecimal.ZERO);
                }
                BigDecimal toppingExtra = topping.getExtraPrice() != null ? topping.getExtraPrice() : BigDecimal.ZERO;
                toppingExtras = toppingExtras.add(toppingExtra);
                itemResult.getComboToppingsSnapshot().add(new ValidationDto.ComboToppingSnapshot(
                        comboItem.getId(), product.getId(), product.resolveName(lang), topping.getId(),
                        topping.resolveToppingName(lang), toppingExtra.multiply(BigDecimal.valueOf(componentQuantity)),
                        toppingExtra, componentQuantity,
                        product.getName(), topping.getToppingName()));
            }

            totalExtraPrice = totalExtraPrice.add(optionExtras.add(toppingExtras)
                    .multiply(BigDecimal.valueOf(componentQuantity)));
        }

        return new ComboSelectionValidation(true, null, totalExtraPrice);
    }
}
