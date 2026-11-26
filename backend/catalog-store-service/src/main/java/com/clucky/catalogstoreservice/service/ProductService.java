package com.clucky.catalogstoreservice.service;

import com.clucky.catalogstoreservice.config.LocaleResolverHelper;
import com.clucky.catalogstoreservice.dto.LocalizedText;
import com.clucky.catalogstoreservice.dto.MediaDto;
import com.clucky.catalogstoreservice.dto.PageResponse;
import com.clucky.catalogstoreservice.dto.ProductDto;
import com.clucky.catalogstoreservice.entity.*;
import com.clucky.catalogstoreservice.exception.ResourceNotFoundException;
import com.clucky.catalogstoreservice.exception.ApiException;
import com.clucky.catalogstoreservice.exception.ErrorCode;
import com.clucky.catalogstoreservice.media.CloudinaryMediaAdapter;
import com.clucky.catalogstoreservice.repository.*;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductOptionRepository optionRepository;
    private final ProductToppingRepository toppingRepository;
    private final ComboItemRepository comboItemRepository;
    private final StoreRepository storeRepository;
    private final StoreProductRepository storeProductRepository;
    private final StoreInventoryRepository storeInventoryRepository;
    private final CloudinaryMediaAdapter mediaAdapter;
    private final CatalogCacheService cacheService;
    private final CatalogEvents events;

    public ProductService(ProductRepository productRepository,
                          CategoryRepository categoryRepository,
                          ProductOptionRepository optionRepository,
                          ProductToppingRepository toppingRepository,
                          ComboItemRepository comboItemRepository,
                          StoreRepository storeRepository,
                          StoreProductRepository storeProductRepository,
                          StoreInventoryRepository storeInventoryRepository,
                          CloudinaryMediaAdapter mediaAdapter,
                          CatalogCacheService cacheService, CatalogEvents events) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.optionRepository = optionRepository;
        this.toppingRepository = toppingRepository;
        this.comboItemRepository = comboItemRepository;
        this.storeRepository = storeRepository;
        this.storeProductRepository = storeProductRepository;
        this.storeInventoryRepository = storeInventoryRepository;
        this.mediaAdapter = mediaAdapter;
        this.cacheService = cacheService;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<ProductDto.Response> getProducts(Long storeId, Long categoryId, String keyword, Integer spicyLevel, boolean activeOnly) {
        return getProducts(storeId, categoryId, keyword, spicyLevel, activeOnly, LocaleResolverHelper.currentRequestLang());
    }

    @Transactional(readOnly = true)
    public List<ProductDto.Response> getProducts(Long storeId, Long categoryId, String keyword, Integer spicyLevel, boolean activeOnly, String lang) {
        List<ProductDto.Response> all = activeOnly ? getOrLoadStoreProducts(storeId) : loadFullProductsFromDb(storeId);
        List<ProductDto.Response> filtered = filterProducts(all, categoryId, keyword, spicyLevel, activeOnly);
        return localizeResponses(filtered, lang);
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductDto.Response> getProductsPaged(Long storeId, Long categoryId, String keyword, Integer spicyLevel, boolean activeOnly, Pageable pageable) {
        return getProductsPaged(storeId, categoryId, keyword, spicyLevel, activeOnly, pageable, LocaleResolverHelper.currentRequestLang());
    }

    @Transactional(readOnly = true)
    public PageResponse<ProductDto.Response> getProductsPaged(Long storeId, Long categoryId, String keyword, Integer spicyLevel, boolean activeOnly, Pageable pageable, String lang) {
        List<ProductDto.Response> all = activeOnly ? getOrLoadStoreProducts(storeId) : loadFullProductsFromDb(storeId);
        List<ProductDto.Response> filtered = filterProducts(all, categoryId, keyword, spicyLevel, activeOnly);

        int totalElements = filtered.size();
        int pageSize = pageable.getPageSize();
        int pageNumber = pageable.getPageNumber();
        int totalPages = pageSize > 0 ? (int) Math.ceil((double) totalElements / pageSize) : 0;

        int fromIndex = (int) Math.min(pageable.getOffset(), totalElements);
        int toIndex = (int) Math.min((long) fromIndex + pageSize, totalElements);
        List<ProductDto.Response> content = localizeResponses(filtered.subList(fromIndex, toIndex), lang);

        return new PageResponse<>(content, pageNumber, pageSize, totalElements, totalPages);
    }

    private List<ProductDto.Response> getOrLoadStoreProducts(Long storeId) {
        List<ProductDto.Response> cached = cacheService.getCachedMenu(storeId);
        if (cached != null) {
            return cached;
        }

        List<ProductDto.Response> loaded = loadFullProductsFromDb(storeId);
        cacheService.setCachedMenu(storeId, loaded);
        return loaded;
    }

    private List<ProductDto.Response> loadFullProductsFromDb(Long storeId) {
        List<Product> products = productRepository.findAllWithCategory();

        Map<Long, StoreProduct> storeProductMap = new HashMap<>();
        Map<Long, StoreInventory> storeInventoryMap = new HashMap<>();
        if (storeId != null) {
            storeRepository.findById(storeId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy chi nhánh với mã: " + storeId));
            List<StoreProduct> spList = storeProductRepository.findByStoreId(storeId);
            for (StoreProduct sp : spList) {
                storeProductMap.put(sp.getProduct().getId(), sp);
            }
            List<StoreInventory> siList = storeInventoryRepository.findByStoreId(storeId);
            for (StoreInventory si : siList) {
                storeInventoryMap.put(si.getProduct().getId(), si);
            }
        }

        return products.stream().map(p -> {
            ProductDto.Response resp = toResponse(p);
            if (storeId != null) {
                StoreProduct sp = storeProductMap.get(p.getId());
                StoreInventory si = storeInventoryMap.get(p.getId());
                if (si != null) {
                    resp.setInventoryVersion(si.getVersion());
                }
                if (sp != null) {
                    if (sp.getStorePrice() != null) {
                        resp.setBasePrice(sp.getStorePrice());
                        resp.setPrice(sp.getStorePrice());
                    }
                    boolean soldOut = Boolean.TRUE.equals(sp.getIsSoldOut()) || si == null || si.getQuantityAvailable() <= 0;
                    resp.setIsSoldOut(soldOut);
                    resp.setIsAvailable(Boolean.TRUE.equals(sp.getIsAvailable()) && !soldOut);
                } else {
                    resp.setIsSoldOut(true);
                    resp.setIsAvailable(false);
                }
            }
            return resp;
        }).collect(Collectors.toList());
    }

    private List<ProductDto.Response> filterProducts(List<ProductDto.Response> list,
                                                     Long categoryId,
                                                     String keyword,
                                                     Integer spicyLevel,
                                                     boolean activeOnly) {
        String trimmed = keyword != null && !keyword.trim().isEmpty() ? keyword.trim() : null;
        return list.stream().filter(p -> {
            if (activeOnly && !Boolean.TRUE.equals(p.getIsActive())) {
                return false;
            }
            if (categoryId != null && !categoryId.equals(p.getCategoryId())) {
                return false;
            }
            if (spicyLevel != null && !spicyLevel.equals(p.getSpicyLevel())) {
                return false;
            }
            if (trimmed != null) {
                boolean nameMatch = (p.getNameI18n() != null && p.getNameI18n().matches(trimmed))
                        || LocalizedText.normalizeForSearch(p.getName()).contains(LocalizedText.normalizeForSearch(trimmed));
                boolean descMatch = (p.getDescriptionI18n() != null && p.getDescriptionI18n().matches(trimmed))
                        || LocalizedText.normalizeForSearch(p.getDescription()).contains(LocalizedText.normalizeForSearch(trimmed));
                boolean catMatch = (p.getCategoryNameI18n() != null && p.getCategoryNameI18n().matches(trimmed))
                        || LocalizedText.normalizeForSearch(p.getCategoryName()).contains(LocalizedText.normalizeForSearch(trimmed));
                if (!nameMatch && !descMatch && !catMatch) {
                    return false;
                }
            }
            return true;
        }).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ProductDto.Response getProductById(Long id, Long storeId) {
        return getProductById(id, storeId, LocaleResolverHelper.currentRequestLang());
    }

    @Transactional(readOnly = true)
    public ProductDto.Response getProductById(Long id, Long storeId, String lang) {
        Product p = productRepository.findByIdWithCategory(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));

        ProductDto.Response resp = toResponse(p, lang);
        if (storeId != null) {
            storeRepository.findById(storeId)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy chi nhánh với mã: " + storeId));
            Optional<StoreProduct> spOpt = storeProductRepository.findByStoreIdAndProductId(storeId, id);
            Optional<StoreInventory> siOpt = storeInventoryRepository.findByStoreIdAndProductId(storeId, id);
            boolean soldOut = spOpt.isEmpty() || siOpt.isEmpty()
                    || spOpt.map(sp -> Boolean.TRUE.equals(sp.getIsSoldOut())).orElse(false)
                    || siOpt.map(si -> si.getQuantityAvailable() <= 0).orElse(false);

            spOpt.ifPresent(sp -> {
                if (sp.getStorePrice() != null) {
                    resp.setBasePrice(sp.getStorePrice());
                    resp.setPrice(sp.getStorePrice());
                }
                resp.setIsAvailable(Boolean.TRUE.equals(sp.getIsAvailable()) && !soldOut);
            });
            if (spOpt.isEmpty()) {
                resp.setIsAvailable(false);
            }
            resp.setIsSoldOut(soldOut);
        }
        return resp;
    }

    public ProductDto.Response createProduct(ProductDto.Request request) {
        CloudinaryMediaAdapter.requireUnchangedImage(request.getImageUrl(), request.getImagePublicId(), null, null);
        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy danh mục với mã: " + request.getCategoryId()));

        Product product = new Product();
        product.setCategory(category);
        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setBasePrice(request.getBasePrice());
        product.setImageUrl(request.getImageUrl());
        product.setImagePublicId(request.getImagePublicId());
        product.setKcal(request.getKcal());
        product.setSpicyLevel(request.getSpicyLevel() != null ? request.getSpicyLevel() : 0);
        product.setIsActive(request.getIsActive() != null ? request.getIsActive() : true);

        applyOptionsAndToppings(product, request.getOptions(), request.getToppings());

        Product saved = productRepository.saveAndFlush(product);
        events.audit("PRODUCT_CREATED", "Product", saved.getId(), null);

        // Auto initialize store_products and store_inventory across all existing stores
        List<Store> stores = storeRepository.findAll();
        for (Store s : stores) {
            StoreProduct sp = new StoreProduct(null, s, saved, null, true, false);
            storeProductRepository.save(sp);

            StoreInventory si = new StoreInventory(null, s, saved, 0, 10, null);
            storeInventoryRepository.save(si);
        }

        cacheService.evictAllStoreMenuCaches();
        return toResponse(saved);
    }

    public ProductDto.Response updateProduct(Long id, ProductDto.Request request) {
        events.expect("products", id, request.getExpectedVersion());
        Product product = productRepository.findByIdWithCategory(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy món ăn với mã: " + id));
        CloudinaryMediaAdapter.requireUnchangedImage(request.getImageUrl(), request.getImagePublicId(), product.getImageUrl(), product.getImagePublicId());

        if (request.getCategoryId() != null) {
            Category category = categoryRepository.findById(request.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy danh mục với mã: " + request.getCategoryId()));
            product.setCategory(category);
        }

        if (request.getName() != null && request.getName().hasText()) product.setName(request.getName());
        if (request.getDescription() != null && request.getDescription().hasText()) product.setDescription(request.getDescription());
        if (request.getBasePrice() != null) product.setBasePrice(request.getBasePrice());
        if (request.getImageUrl() != null) product.setImageUrl(request.getImageUrl());
        if (request.getImagePublicId() != null) product.setImagePublicId(request.getImagePublicId());
        if (request.getKcal() != null) product.setKcal(request.getKcal());
        if (request.getSpicyLevel() != null) product.setSpicyLevel(request.getSpicyLevel());
        if (request.getIsActive() != null) product.setIsActive(request.getIsActive());

        if (request.getOptions() != null || request.getToppings() != null) {
            applyOptionsAndToppings(product, request.getOptions(), request.getToppings());
        }

        Product saved = productRepository.saveAndFlush(product);
        events.audit("PRODUCT_UPDATED", "Product", id, null);
        cacheService.evictAllStoreMenuCaches();
        return toResponse(saved);
    }

    public ProductDto.Response updateProductStatus(Long id, boolean isActive, Long expectedVersion) {
        events.expect("products", id, expectedVersion);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy món ăn với mã: " + id));
        product.setIsActive(isActive);
        Product saved = productRepository.saveAndFlush(product);
        events.audit("PRODUCT_STATUS_CHANGED", "Product", id, null);
        cacheService.evictAllStoreMenuCaches();
        return toResponse(saved);
    }

    public void deleteProduct(Long id, Long expectedVersion) {
        events.expect("products", id, expectedVersion);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy món ăn với mã: " + id));

        String oldPublicId = product.getImagePublicId();
        storeInventoryRepository.deleteByProductId(id);
        storeProductRepository.deleteByProductId(id);
        productRepository.delete(product);
        productRepository.flush();
        events.audit("PRODUCT_DELETED", "Product", id, null);

        if (oldPublicId != null && !oldPublicId.isBlank()) {
            mediaAdapter.deleteOldAssetAfterCommit(oldPublicId);
        }
        cacheService.evictAllStoreMenuCaches();
    }

    public ProductDto.Response uploadProductImage(Long id, MultipartFile file, Long expectedVersion) {
        events.expect("products", id, expectedVersion);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy món ăn với mã: " + id));

        String oldPublicId = product.getImagePublicId();
        MediaDto.UploadResponse uploadRes = mediaAdapter.uploadImage(file, "products");

        product.setImageUrl(uploadRes.getSecureUrl());
        product.setImagePublicId(uploadRes.getPublicId());
        Product saved = productRepository.saveAndFlush(product);
        events.audit("PRODUCT_IMAGE_REPLACED", "Product", id, null);

        if (oldPublicId != null && !oldPublicId.isBlank() && !oldPublicId.equals(uploadRes.getPublicId())) {
            mediaAdapter.deleteOldAssetAfterCommit(oldPublicId);
        }

        cacheService.evictAllStoreMenuCaches();
        return toResponse(saved);
    }

    private void applyOptionsAndToppings(Product product,
                                         List<ProductDto.OptionGroupDto> optionsDto,
                                         List<ProductDto.ToppingDto> toppingsDto) {
        if (optionsDto != null) {
            Map<Long, ProductOption> remainingOptions = product.getOptions().stream()
                    .collect(Collectors.toMap(ProductOption::getId, option -> option));
            List<ProductOption> retainedOptions = new ArrayList<>();
            for (ProductDto.OptionGroupDto group : optionsDto) {
                if (group.getChoices() != null) {
                    for (ProductDto.OptionChoiceDto choice : group.getChoices()) {
                        Long optionId = persistedItemId(choice.getId(), "opt-");
                        LocalizedText groupName = group.effectiveName();
                        LocalizedText choiceName = choice.effectiveName();
                        ProductOption option = optionId == null
                                ? new ProductOption(null, product, groupName, choiceName, choice.getPriceAdjustment(), group.isRequired())
                                : remainingOptions.remove(optionId);
                        if (option == null) throw new ApiException(ErrorCode.VALIDATION_ERROR);
                        option.setGroupName(groupName);
                        option.setName(choiceName);
                        option.setExtraPrice(choice.getPriceAdjustment());
                        option.setIsRequired(group.isRequired());
                        retainedOptions.add(option);
                    }
                }
            }
            if (!remainingOptions.isEmpty()
                    && comboItemRepository.existsByOptionIdIn(new ArrayList<>(remainingOptions.keySet()))) {
                throw new ApiException(ErrorCode.RESOURCE_CONFLICT);
            }
            product.getOptions().removeIf(option -> !retainedOptions.contains(option));
            for (ProductOption option : retainedOptions) {
                if (!product.getOptions().contains(option)) product.getOptions().add(option);
            }
        }

        if (toppingsDto != null) {
            Map<Long, ProductTopping> remainingToppings = product.getToppings().stream()
                    .collect(Collectors.toMap(ProductTopping::getId, topping -> topping));
            List<ProductTopping> retainedToppings = new ArrayList<>();
            for (ProductDto.ToppingDto toppingDto : toppingsDto) {
                Long toppingId = persistedItemId(toppingDto.getId(), "top-");
                LocalizedText toppingName = toppingDto.effectiveName();
                ProductTopping topping = toppingId == null
                        ? new ProductTopping(null, product, toppingName, toppingDto.getPrice())
                        : remainingToppings.remove(toppingId);
                if (topping == null) throw new ApiException(ErrorCode.VALIDATION_ERROR);
                topping.setToppingName(toppingName);
                topping.setExtraPrice(toppingDto.getPrice());
                retainedToppings.add(topping);
            }
            product.getToppings().removeIf(topping -> !retainedToppings.contains(topping));
            for (ProductTopping topping : retainedToppings) {
                if (!product.getToppings().contains(topping)) product.getToppings().add(topping);
            }
        }
    }

    private Long persistedItemId(String value, String prefix) {
        if (value == null || value.startsWith("new-")) return null;
        if (!value.startsWith(prefix)) throw new ApiException(ErrorCode.VALIDATION_ERROR);
        try {
            long itemId = Long.parseLong(value.substring(prefix.length()));
            if (itemId <= 0) throw new ApiException(ErrorCode.VALIDATION_ERROR);
            return itemId;
        } catch (NumberFormatException failure) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
    }

    public ProductDto.Response toResponse(Product p) {
        return toResponse(p, LocaleResolverHelper.currentRequestLang());
    }

    public ProductDto.Response toResponse(Product p, String lang) {
        ProductDto.Response r = new ProductDto.Response();
        r.setId(p.getId());
        r.setVersion(p.getVersion());
        String slugSource = p.getName() != null ? p.resolveName("vi") : "";
        r.setSlug(ProductDto.toSlug(slugSource) + "-" + p.getId());
        String resolvedName = p.resolveName(lang);
        r.setName(resolvedName);
        r.setNameI18n(p.getName());
        if (p.getCategory() != null) {
            r.setCategoryId(p.getCategory().getId());
            r.setCategoryName(p.getCategory().resolveName(lang));
            r.setCategoryNameI18n(p.getCategory().getName());
        }
        r.setDescription(p.resolveDescription(lang));
        r.setDescriptionI18n(p.getDescription());
        r.setBasePrice(p.getBasePrice());
        r.setImageUrl(p.getImageUrl());
        r.setImagePublicId(p.getImagePublicId());
        r.setAlt((resolvedName != null ? resolvedName : "") + " - Clucky");
        r.setKcal(p.getKcal());
        r.setSpicyLevel(p.getSpicyLevel());
        r.setIsActive(p.getIsActive());

        // Group options by canonical LocalizedText groupName
        Map<LocalizedText, List<ProductOption>> rawGroupMap = new LinkedHashMap<>();
        List<ProductOption> options = optionRepository.findByProductId(p.getId());
        for (ProductOption opt : options) {
            rawGroupMap.computeIfAbsent(opt.getGroupName(), k -> new ArrayList<>()).add(opt);
        }
        List<ProductDto.OptionGroupDto> optionGroups = new ArrayList<>();
        int gIndex = 1;
        for (Map.Entry<LocalizedText, List<ProductOption>> entry : rawGroupMap.entrySet()) {
            boolean isRequired = entry.getValue().stream()
                    .anyMatch(opt -> opt.getIsRequired() == null || Boolean.TRUE.equals(opt.getIsRequired()));
            List<ProductDto.OptionChoiceDto> choices = entry.getValue().stream()
                    .map(opt -> new ProductDto.OptionChoiceDto(
                            "opt-" + opt.getId(),
                            opt.resolveName(lang),
                            opt.getName(),
                            opt.getExtraPrice()
                    )).collect(Collectors.toList());
            optionGroups.add(new ProductDto.OptionGroupDto(
                    "grp-" + (gIndex++),
                    LocalizedText.resolve(entry.getKey(), lang),
                    entry.getKey(),
                    isRequired,
                    choices
            ));
        }
        r.setOptions(optionGroups);

        // Toppings
        List<ProductDto.ToppingDto> toppings = new ArrayList<>();
        List<ProductTopping> toppingsList = toppingRepository.findByProductId(p.getId());
        for (ProductTopping top : toppingsList) {
            toppings.add(new ProductDto.ToppingDto(
                    "top-" + top.getId(),
                    top.resolveToppingName(lang),
                    top.getToppingName(),
                    top.getExtraPrice()
            ));
        }
        r.setToppings(toppings);

        return r;
    }

    private List<ProductDto.Response> localizeResponses(List<ProductDto.Response> items, String lang) {
        return items.stream().map(item -> localizeResponse(item, lang)).collect(Collectors.toList());
    }

    private ProductDto.Response localizeResponse(ProductDto.Response src, String lang) {
        ProductDto.Response copy = new ProductDto.Response();
        copy.setVersion(src.getVersion());
        copy.setInventoryVersion(src.getInventoryVersion());
        copy.setId(src.getId());
        copy.setSlug(src.getSlug());
        String resolvedName = src.getNameI18n() != null ? src.getNameI18n().resolve(lang) : src.getName();
        copy.setName(resolvedName);
        copy.setNameI18n(src.getNameI18n());
        copy.setCategoryId(src.getCategoryId());
        String resolvedCat = src.getCategoryNameI18n() != null ? src.getCategoryNameI18n().resolve(lang) : src.getCategoryName();
        copy.setCategoryName(resolvedCat);
        copy.setCategoryNameI18n(src.getCategoryNameI18n());
        String resolvedDesc = src.getDescriptionI18n() != null ? src.getDescriptionI18n().resolve(lang) : src.getDescription();
        copy.setDescription(resolvedDesc);
        copy.setDescriptionI18n(src.getDescriptionI18n());
        copy.setBasePrice(src.getBasePrice());
        copy.setPrice(src.getPrice());
        copy.setImageUrl(src.getImageUrl());
        copy.setImagePublicId(src.getImagePublicId());
        copy.setAlt((resolvedName != null ? resolvedName : "") + " - Clucky");
        copy.setKcal(src.getKcal());
        copy.setSpicyLevel(src.getSpicyLevel());
        copy.setIsActive(src.getIsActive());
        copy.setIsAvailable(src.getIsAvailable());
        copy.setIsSoldOut(src.getIsSoldOut());
        copy.setIsBestSeller(src.getIsBestSeller());
        copy.setRating(src.getRating());
        copy.setReviewCount(src.getReviewCount());

        if (src.getOptions() != null) {
            List<ProductDto.OptionGroupDto> localizedGroups = new ArrayList<>(src.getOptions().size());
            for (ProductDto.OptionGroupDto grp : src.getOptions()) {
                List<ProductDto.OptionChoiceDto> localizedChoices = new ArrayList<>(grp.getChoices().size());
                for (ProductDto.OptionChoiceDto ch : grp.getChoices()) {
                    String chName = ch.getNameI18n() != null ? ch.getNameI18n().resolve(lang) : ch.getName();
                    localizedChoices.add(new ProductDto.OptionChoiceDto(ch.getId(), chName, ch.getNameI18n(), ch.getPriceAdjustment()));
                }
                String grpName = grp.getNameI18n() != null ? grp.getNameI18n().resolve(lang) : grp.getName();
                localizedGroups.add(new ProductDto.OptionGroupDto(grp.getId(), grpName, grp.getNameI18n(), grp.isRequired(), localizedChoices));
            }
            copy.setOptions(localizedGroups);
        }

        if (src.getToppings() != null) {
            List<ProductDto.ToppingDto> localizedToppings = new ArrayList<>(src.getToppings().size());
            for (ProductDto.ToppingDto top : src.getToppings()) {
                String topName = top.getNameI18n() != null ? top.getNameI18n().resolve(lang) : top.getName();
                localizedToppings.add(new ProductDto.ToppingDto(top.getId(), topName, top.getNameI18n(), top.getPrice()));
            }
            copy.setToppings(localizedToppings);
        }

        return copy;
    }
}
