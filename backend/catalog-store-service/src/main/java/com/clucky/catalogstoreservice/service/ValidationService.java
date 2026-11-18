package com.clucky.catalogstoreservice.service;

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
        ValidationDto.Response response = new ValidationDto.Response();
        List<ValidationDto.ValidatedItem> resultItems = new ArrayList<>();
        BigDecimal totalSubtotal = BigDecimal.ZERO;
        boolean allValid = true;

        if (request.getStoreId() == null) {
            response.setValid(false);
            response.setMessage("storeId không được để trống!");
            return response;
        }

        Optional<Store> storeOpt = storeRepository.findById(request.getStoreId());
        if (storeOpt.isEmpty()) {
            response.setValid(false);
            response.setStoreOpen(false);
            response.setMessage("Không tìm thấy chi nhánh với mã: " + request.getStoreId());
            return response;
        }

        Store store = storeOpt.get();
        if (!storeService.isStoreServingNow(store)) {
            response.setStoreOpen(false);
            response.setValid(false);
            String message;
            if (store.getStatus() == StoreStatus.INACTIVE) {
                message = "Chi nhánh '" + store.getName() + "' hiện đã ngừng kinh doanh.";
            } else if (store.getStatus() == StoreStatus.CLOSED_TEMP) {
                message = "Chi nhánh '" + store.getName() + "' hiện đang tạm đóng cửa.";
            } else {
                String openStr = store.getOpenTime() != null ? store.getOpenTime().format(TIME_FORMAT) : "";
                String closeStr = store.getCloseTime() != null ? store.getCloseTime().format(TIME_FORMAT) : "";
                message = "Chi nhánh '" + store.getName() + "' hiện đang ngoài giờ phục vụ (Giờ mở cửa: " + openStr + " - " + closeStr + "). Vui lòng quay lại trong giờ hoạt động.";
            }
            response.setMessage(message);
        }

        if (request.getItems() == null || request.getItems().isEmpty()) {
            response.setValid(false);
            response.setMessage("Danh sách món kiểm tra không được để trống!");
            return response;
        }

        // Aggregate total demanded quantities per product across standalone items and combos
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
                // Validate Product
                itemResult.setProductId(itemReq.getProductId());
                if (itemReq.getComboSelections() != null && !itemReq.getComboSelections().isEmpty()) {
                    itemResult.setAvailable(false);
                    itemResult.setReason("Tùy chọn combo chỉ được gửi cho món thuộc combo.");
                    allValid = false;
                }
                Optional<Product> prodOpt = productRepository.findById(itemReq.getProductId());
                if (prodOpt.isEmpty() || !Boolean.TRUE.equals(prodOpt.get().getIsActive())) {
                    itemResult.setAvailable(false);
                    itemResult.setReason("Món ăn không tồn tại hoặc đã ngừng phục vụ.");
                    allValid = false;
                } else {
                    Product prod = prodOpt.get();
                    itemResult.setName(prod.getName());

                    // Check availability & inventory strictly at store
                    Optional<StoreProduct> spOpt = storeProductRepository.findByStoreIdAndProductId(store.getId(), prod.getId());
                    Optional<StoreInventory> siOpt = storeInventoryRepository.findByStoreIdAndProductId(store.getId(), prod.getId());

                    if (spOpt.isEmpty()) {
                        itemResult.setAvailable(false);
                        itemResult.setReason("Món ăn không khả dụng tại chi nhánh này.");
                        allValid = false;
                    } else if (!Boolean.TRUE.equals(spOpt.get().getIsAvailable())) {
                        itemResult.setAvailable(false);
                        itemResult.setReason("Món ăn hiện không được bán tại chi nhánh này.");
                        allValid = false;
                    } else if (Boolean.TRUE.equals(spOpt.get().getIsSoldOut())) {
                        itemResult.setAvailable(false);
                        itemResult.setReason("Món ăn đã hết hàng tại chi nhánh này.");
                        allValid = false;
                    } else if (siOpt.isEmpty()) {
                        itemResult.setAvailable(false);
                        itemResult.setReason("Món ăn không có dữ liệu tồn kho tại chi nhánh này.");
                        allValid = false;
                    } else {
                        siOpt.ifPresent(si -> itemResult.setInventoryVersion(si.getVersion()));
                        int availableStock = siOpt.get().getQuantityAvailable() != null ? siOpt.get().getQuantityAvailable() : 0;
                        long requiredStock = totalDemandedQuantity.getOrDefault(prod.getId(), (long) qty);
                        if (availableStock < requiredStock) {
                            itemResult.setAvailable(false);
                            itemResult.setReason("Món ăn không đủ số lượng tồn kho tại chi nhánh.");
                            allValid = false;
                        }
                    }

                    BigDecimal unitPrice = spOpt.map(StoreProduct::getStorePrice).filter(Objects::nonNull).orElse(prod.getBasePrice());
                    itemResult.setUnitPrice(unitPrice);

                    // Validate required option groups and reject invalid/foreign option IDs
                    List<ProductOption> productOptions = optionRepository.findByProductId(prod.getId());
                    Map<String, List<ProductOption>> groupMap = productOptions.stream()
                            .collect(Collectors.groupingBy(ProductOption::getGroupName, LinkedHashMap::new, Collectors.toList()));
                    Map<Long, ProductOption> validOptionMap = productOptions.stream()
                            .collect(Collectors.toMap(ProductOption::getId, o -> o));

                    BigDecimal optionExtras = BigDecimal.ZERO;
                    boolean optionsValid = true;
                    Set<String> chosenGroups = new HashSet<>();
                    List<Long> requestedOptionIds = itemReq.getOptionIds() != null ? itemReq.getOptionIds() : Collections.emptyList();

                    for (Long optId : requestedOptionIds) {
                        ProductOption opt = validOptionMap.get(optId);
                        if (opt == null) {
                            itemResult.setAvailable(false);
                            itemResult.setReason("Tùy chọn (optionId=" + optId + ") không hợp lệ hoặc không thuộc món ăn này.");
                            allValid = false;
                            optionsValid = false;
                            break;
                        }
                        if (!chosenGroups.add(opt.getGroupName())) {
                            itemResult.setAvailable(false);
                            itemResult.setReason("Không thể chọn nhiều hơn 1 tùy chọn cho nhóm '" + opt.getGroupName() + "'.");
                            allValid = false;
                            optionsValid = false;
                            break;
                        }
                        optionExtras = optionExtras.add(opt.getExtraPrice());
                        itemResult.getOptionsSnapshot().add(new ValidationDto.OptionSnapshot(
                                opt.getId(), opt.getGroupName(), opt.getName(), opt.getExtraPrice()));
                    }

                    if (optionsValid) {
                        for (Map.Entry<String, List<ProductOption>> entry : groupMap.entrySet()) {
                            String groupName = entry.getKey();
                            boolean isRequired = entry.getValue().stream()
                                    .anyMatch(opt -> opt.getIsRequired() == null || Boolean.TRUE.equals(opt.getIsRequired()));
                            if (isRequired && !chosenGroups.contains(groupName)) {
                                itemResult.setAvailable(false);
                                itemResult.setReason("Thiếu tùy chọn bắt buộc cho nhóm '" + groupName + "'.");
                                allValid = false;
                                optionsValid = false;
                                break;
                            }
                        }
                    }

                    // Validate toppings and reject invalid/foreign topping IDs
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
                            itemResult.setReason("Topping (toppingId=" + topId + ") không hợp lệ hoặc không thuộc món ăn này.");
                            allValid = false;
                            toppingsValid = false;
                            break;
                        }
                        toppingExtras = toppingExtras.add(top.getExtraPrice());
                        itemResult.getToppingsSnapshot().add(new ValidationDto.ToppingSnapshot(
                                top.getId(), top.getToppingName(), top.getExtraPrice()));
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
                // Validate Combo
                itemResult.setComboId(itemReq.getComboId());
                Combo combo = comboCache.computeIfAbsent(itemReq.getComboId(), id ->
                        comboRepository.findByIdWithItems(id).orElse(null)
                );

                if (combo == null || !Boolean.TRUE.equals(combo.getIsActive())) {
                    itemResult.setAvailable(false);
                    itemResult.setReason("Combo không tồn tại hoặc đã ngừng áp dụng.");
                    allValid = false;
                } else {
                    OffsetDateTime now = OffsetDateTime.now();
                    if (combo.getStartAt() != null && now.isBefore(combo.getStartAt())) {
                        itemResult.setAvailable(false);
                        itemResult.setReason("Combo chưa đến thời gian áp dụng.");
                        allValid = false;
                    } else if (combo.getEndAt() != null && now.isAfter(combo.getEndAt())) {
                        itemResult.setAvailable(false);
                        itemResult.setReason("Combo đã hết thời gian áp dụng.");
                        allValid = false;
                    } else if (combo.getItems() == null || combo.getItems().isEmpty()) {
                        itemResult.setAvailable(false);
                        itemResult.setReason("Combo chưa có món thành phần.");
                        allValid = false;
                    } else {
                        // Check availability and stock for each combo component
                        for (ComboItem ci : combo.getItems()) {
                            Product cp = ci.getProduct();
                            if (cp == null || !Boolean.TRUE.equals(cp.getIsActive())) {
                                itemResult.setAvailable(false);
                                itemResult.setReason("Món '" + (cp != null ? cp.getName() : "thành phần") + "' trong combo đã ngừng phục vụ.");
                                allValid = false;
                                break;
                            }

                            Optional<StoreProduct> spOpt = storeProductRepository.findByStoreIdAndProductId(store.getId(), cp.getId());
                            Optional<StoreInventory> siOpt = storeInventoryRepository.findByStoreIdAndProductId(store.getId(), cp.getId());

                            if (spOpt.isEmpty()) {
                                itemResult.setAvailable(false);
                                itemResult.setReason("Món '" + cp.getName() + "' trong combo không khả dụng tại chi nhánh này.");
                                allValid = false;
                                break;
                            }
                            if (!Boolean.TRUE.equals(spOpt.get().getIsAvailable())) {
                                itemResult.setAvailable(false);
                                itemResult.setReason("Món '" + cp.getName() + "' trong combo không được bán tại chi nhánh này.");
                                allValid = false;
                                break;
                            }
                            if (Boolean.TRUE.equals(spOpt.get().getIsSoldOut())) {
                                itemResult.setAvailable(false);
                                itemResult.setReason("Món '" + cp.getName() + "' trong combo đã hết hàng tại chi nhánh này.");
                                allValid = false;
                                break;
                            }
                            if (siOpt.isEmpty()) {
                                itemResult.setAvailable(false);
                                itemResult.setReason("Món '" + cp.getName() + "' trong combo không có dữ liệu tồn kho tại chi nhánh này.");
                                allValid = false;
                                break;
                            }
                            int availableStock = siOpt.get().getQuantityAvailable() != null ? siOpt.get().getQuantityAvailable() : 0;
                            long requiredStock = totalDemandedQuantity.getOrDefault(cp.getId(), 0L);
                            if (availableStock < requiredStock) {
                                itemResult.setAvailable(false);
                                itemResult.setReason("Món '" + cp.getName() + "' trong combo không đủ tồn kho tại chi nhánh.");
                                allValid = false;
                                break;
                            }
                        }
                    }

                    boolean hasFlatSelections = (itemReq.getOptionIds() != null && !itemReq.getOptionIds().isEmpty())
                            || (itemReq.getToppingIds() != null && !itemReq.getToppingIds().isEmpty());
                    if (hasFlatSelections) {
                        itemResult.setAvailable(false);
                        itemResult.setReason("Tùy chọn của combo phải được gửi theo từng comboItemId.");
                        allValid = false;
                    } else {
                        ComboSelectionValidation selectionValidation = validateComboSelections(
                                combo, itemReq.getComboSelections(), itemResult);
                        if (!selectionValidation.valid()) {
                            itemResult.setAvailable(false);
                            itemResult.setReason(selectionValidation.reason());
                            allValid = false;
                        } else {
                            itemResult.setExtraPrice(selectionValidation.extraPrice());
                        }
                    }

                    itemResult.setName(combo.getName());
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
                itemResult.setReason("Yêu cầu món thiếu cả productId và comboId.");
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
                    : "Một số món trong giỏ hàng không khả dụng tại chi nhánh đã chọn.");
        } else if (response.isValid()) {
            response.setMessage("Toàn bộ món ăn hợp lệ và sẵn sàng phục vụ.");
        }

        return response;
    }

    private record ComboSelectionValidation(boolean valid, String reason, BigDecimal extraPrice) {}

    private ComboSelectionValidation validateComboSelections(
            Combo combo,
            List<ValidationDto.ComboSelectionRequest> requestedSelections,
            ValidationDto.ValidatedItem itemResult) {
        Map<Long, ComboItem> comboItemsById = new LinkedHashMap<>();
        for (ComboItem comboItem : combo.getItems()) {
            if (comboItem.getId() == null || comboItemsById.put(comboItem.getId(), comboItem) != null) {
                return new ComboSelectionValidation(false, "Combo có thành phần không hợp lệ.", BigDecimal.ZERO);
            }
        }

        Map<Long, ValidationDto.ComboSelectionRequest> selectionsByItemId = new HashMap<>();
        if (requestedSelections != null) {
            for (ValidationDto.ComboSelectionRequest selection : requestedSelections) {
                if (selection == null || selection.comboItemId() == null
                        || !comboItemsById.containsKey(selection.comboItemId())) {
                    return new ComboSelectionValidation(false,
                            "comboItemId không thuộc combo đang được đặt.", BigDecimal.ZERO);
                }
                if (selectionsByItemId.put(selection.comboItemId(), selection) != null) {
                    return new ComboSelectionValidation(false,
                            "Không thể gửi nhiều lựa chọn cho cùng một thành phần combo.", BigDecimal.ZERO);
                }
            }
        }

        BigDecimal totalExtraPrice = BigDecimal.ZERO;
        for (ComboItem comboItem : combo.getItems()) {
            Product product = comboItem.getProduct();
            if (product == null || product.getId() == null) {
                return new ComboSelectionValidation(false, "Món thành phần của combo không hợp lệ.", BigDecimal.ZERO);
            }

            int componentQuantity = comboItem.getQuantity() != null && comboItem.getQuantity() > 0
                    ? comboItem.getQuantity() : 1;
            List<ProductOption> productOptions = optionRepository.findByProductId(product.getId());
            Map<Long, ProductOption> optionsById = productOptions.stream()
                    .collect(Collectors.toMap(ProductOption::getId, option -> option));
            Map<String, List<ProductOption>> optionsByGroup = productOptions.stream()
                    .collect(Collectors.groupingBy(ProductOption::getGroupName, LinkedHashMap::new, Collectors.toList()));

            ProductOption fixedOption = null;
            if (comboItem.getOptionId() != null) {
                fixedOption = optionsById.get(comboItem.getOptionId());
                if (fixedOption == null) {
                    return new ComboSelectionValidation(false,
                            "Tùy chọn cố định của món '" + product.getName() + "' không còn hợp lệ.", BigDecimal.ZERO);
                }
                itemResult.getComboOptionsSnapshot().add(new ValidationDto.ComboOptionSnapshot(
                        comboItem.getId(), product.getId(), product.getName(), fixedOption.getId(),
                        fixedOption.getGroupName(), fixedOption.getName(), BigDecimal.ZERO, BigDecimal.ZERO,
                        componentQuantity, true));
            }

            ValidationDto.ComboSelectionRequest selection = selectionsByItemId.get(comboItem.getId());
            List<Long> requestedOptionIds = selection != null && selection.optionIds() != null
                    ? selection.optionIds() : List.of();
            Set<Long> seenOptionIds = new HashSet<>();
            Set<String> chosenGroups = new HashSet<>();
            if (fixedOption != null) chosenGroups.add(fixedOption.getGroupName());
            boolean hasDynamicOptionSelection = false;
            BigDecimal optionExtras = BigDecimal.ZERO;

            for (Long optionId : requestedOptionIds) {
                if (optionId == null || !seenOptionIds.add(optionId)) {
                    return new ComboSelectionValidation(false,
                            "Danh sách tùy chọn combo chứa mã trống hoặc bị lặp.", BigDecimal.ZERO);
                }
                ProductOption option = optionsById.get(optionId);
                if (option == null) {
                    return new ComboSelectionValidation(false,
                            "Tùy chọn (optionId=" + optionId + ") không thuộc món '" + product.getName() + "'.", BigDecimal.ZERO);
                }
                if (fixedOption != null && Objects.equals(option.getGroupName(), fixedOption.getGroupName())) {
                    if (Objects.equals(option.getId(), fixedOption.getId())) {
                        // The fixed option is already included in comboPrice. It may be echoed by a client,
                        // but it is never charged as a customer-selected extra.
                        continue;
                    }
                    return new ComboSelectionValidation(false,
                            "Nhóm '" + fixedOption.getGroupName() + "' đã có lựa chọn cố định trong combo.", BigDecimal.ZERO);
                }
                if (!chosenGroups.add(option.getGroupName())) {
                    return new ComboSelectionValidation(false,
                            "Không thể chọn nhiều hơn 1 tùy chọn cho nhóm '" + option.getGroupName() + "'.", BigDecimal.ZERO);
                }
                hasDynamicOptionSelection = true;
                BigDecimal optionExtra = option.getExtraPrice() != null ? option.getExtraPrice() : BigDecimal.ZERO;
                optionExtras = optionExtras.add(optionExtra);
                itemResult.getComboOptionsSnapshot().add(new ValidationDto.ComboOptionSnapshot(
                        comboItem.getId(), product.getId(), product.getName(), option.getId(),
                        option.getGroupName(), option.getName(),
                        optionExtra.multiply(BigDecimal.valueOf(componentQuantity)), optionExtra,
                        componentQuantity, false));
            }

            // Required groups remain optional for legacy combos. If a customer starts choosing
            // dynamic options for this component, enforce the required groups that are not fixed.
            if (hasDynamicOptionSelection) {
                for (Map.Entry<String, List<ProductOption>> group : optionsByGroup.entrySet()) {
                    boolean required = group.getValue().stream()
                            .anyMatch(option -> option.getIsRequired() == null || Boolean.TRUE.equals(option.getIsRequired()));
                    if (required && !chosenGroups.contains(group.getKey())) {
                        return new ComboSelectionValidation(false,
                                "Thiếu tùy chọn bắt buộc cho món '" + product.getName() + "', nhóm '" + group.getKey() + "'.",
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
                            "Danh sách topping combo chứa mã trống hoặc bị lặp.", BigDecimal.ZERO);
                }
                ProductTopping topping = toppingsById.get(toppingId);
                if (topping == null) {
                    return new ComboSelectionValidation(false,
                            "Topping (toppingId=" + toppingId + ") không thuộc món '" + product.getName() + "'.", BigDecimal.ZERO);
                }
                BigDecimal toppingExtra = topping.getExtraPrice() != null ? topping.getExtraPrice() : BigDecimal.ZERO;
                toppingExtras = toppingExtras.add(toppingExtra);
                itemResult.getComboToppingsSnapshot().add(new ValidationDto.ComboToppingSnapshot(
                        comboItem.getId(), product.getId(), product.getName(), topping.getId(),
                        topping.getToppingName(), toppingExtra.multiply(BigDecimal.valueOf(componentQuantity)),
                        toppingExtra, componentQuantity));
            }

            totalExtraPrice = totalExtraPrice.add(optionExtras.add(toppingExtras)
                    .multiply(BigDecimal.valueOf(componentQuantity)));
        }

        return new ComboSelectionValidation(true, null, totalExtraPrice);
    }
}
