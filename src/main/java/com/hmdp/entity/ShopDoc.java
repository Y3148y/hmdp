package com.hmdp.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.experimental.Accessors;

/**
 * ES 商铺文档，与 {@link Shop} DB 实体解耦。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class ShopDoc {

    private Long id;
    private String name;
    private String area;
    private String address;
    private Long typeId;
    private Long avgPrice;
    private Integer sold;
    private Integer comments;
    private Integer score;
    private String openHours;
    private Double x;
    private Double y;
    private String images;

    public static ShopDoc from(Shop shop) {
        ShopDoc doc = new ShopDoc();
        doc.id = shop.getId();
        doc.name = shop.getName();
        doc.area = shop.getArea();
        doc.address = shop.getAddress();
        doc.typeId = shop.getTypeId();
        doc.avgPrice = shop.getAvgPrice();
        doc.sold = shop.getSold();
        doc.comments = shop.getComments();
        doc.score = shop.getScore();
        doc.openHours = shop.getOpenHours();
        doc.x = shop.getX();
        doc.y = shop.getY();
        doc.images = shop.getImages();
        return doc;
    }
}
