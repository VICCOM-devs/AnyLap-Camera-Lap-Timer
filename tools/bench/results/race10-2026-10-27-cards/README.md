# Check-card outlines · Race10, 27 Oct 2026

Every car in a Check card's picture outlined with its own calibration picture (`vision/CardFit`),
name tags placed by `vision/LabelLayout` in a band round the picture. Same code in the app tray.

Six seeds (7, 11, 3, 19, 5, 23), 633 cards, 850 outlines:

| check | result |
|---|---|
| a tag over a car or another tag, or off the card (`tagcheck.py`) | 0 |
| an outline named after a car that wasn't at the line (`namecheck.py`) | 0 |
| largest moving patch no outline covers (`covcheck.py`) | ≤ 54 px (a car ≈ 500 px), except seed 23 card 15: 76 px of a black car the race also missed |

Lap results unchanged (100/100 on 7, 11, 3, 19, 5; 98/100 on 23 as before).
Known: the matte black car's outline sits ~10° turned (its calibration picture was captured a few degrees off).
`cards_seed*.png` = every card with two or more cars; `race10_7.html` = the result page.
