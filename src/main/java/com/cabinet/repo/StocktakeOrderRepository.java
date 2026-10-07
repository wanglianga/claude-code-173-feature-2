package com.cabinet.repo;

import com.cabinet.domain.StocktakeOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StocktakeOrderRepository extends JpaRepository<StocktakeOrder, Long> {
    Optional<StocktakeOrder> findByOrderNo(String orderNo);
    List<StocktakeOrder> findByCabinetIdOrderByIdDesc(Long cabinetId);
}
