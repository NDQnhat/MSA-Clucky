package com.clucky.cartorderservice.cart;

import java.util.ArrayList;
import java.util.List;

/** Customer-selected options and toppings for one persisted combo component. */
public class ComboSelectionDto {
    private Long comboItemId;
    private List<Long> optionIds = new ArrayList<>();
    private List<Long> toppingIds = new ArrayList<>();

    public ComboSelectionDto() {}

    public ComboSelectionDto(Long comboItemId, List<Long> optionIds, List<Long> toppingIds) {
        this.comboItemId = comboItemId;
        setOptionIds(optionIds);
        setToppingIds(toppingIds);
    }

    public Long getComboItemId() { return comboItemId; }
    public void setComboItemId(Long comboItemId) { this.comboItemId = comboItemId; }

    public List<Long> getOptionIds() { return optionIds; }
    public void setOptionIds(List<Long> optionIds) {
        this.optionIds = optionIds != null ? new ArrayList<>(optionIds) : new ArrayList<>();
    }

    public List<Long> getToppingIds() { return toppingIds; }
    public void setToppingIds(List<Long> toppingIds) {
        this.toppingIds = toppingIds != null ? new ArrayList<>(toppingIds) : new ArrayList<>();
    }
}
