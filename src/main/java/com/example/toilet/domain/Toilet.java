package com.example.toilet.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
public class Toilet {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String contsName;
    private String addrNew;
    private String addrOld;
    @Column(name = "coord_x")
    private Double coordX;
    @Column(name = "coord_y")
    private Double coordY;
}
