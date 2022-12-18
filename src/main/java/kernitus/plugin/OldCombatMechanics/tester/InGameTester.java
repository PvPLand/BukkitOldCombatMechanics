/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kernitus.plugin.OldCombatMechanics.tester;

import kernitus.plugin.OldCombatMechanics.OCMMain;
import kernitus.plugin.OldCombatMechanics.utilities.Messenger;
import kernitus.plugin.OldCombatMechanics.utilities.damage.ArmourUtils;
import kernitus.plugin.OldCombatMechanics.utilities.damage.DamageUtils;
import kernitus.plugin.OldCombatMechanics.utilities.damage.WeaponDamages;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

public class InGameTester {

    private final OCMMain ocm;
    private Tally tally;
    private CommandSender sender;
    private Player attacker, defender;
    private FakePlayer fakeAttacker, fakeDefender;
    private final Queue<OCMTest> testQueue;

    // todo test with potion effects on attack

    // todo test with enchanted armour
    // todo test with potion effects on defence
    // todo test with shield blocking

    // todo test with bow attacks
    // todo test armour durability

    public InGameTester(OCMMain ocm) {
        this.ocm = ocm;
        testQueue = new ArrayDeque<>();
    }

    /**
     * Perform all tests using the two specified players
     */
    public void performTests(CommandSender sender, Location location) {
        this.sender = sender;
        fakeAttacker = new FakePlayer();
        fakeAttacker.spawn(location.add(2, 0, 0));
        fakeDefender = new FakePlayer();
        fakeDefender.spawn(location.add(0, 0, 2));

        this.attacker = Bukkit.getPlayer(fakeAttacker.getUuid());
        this.defender = Bukkit.getPlayer(fakeDefender.getUuid());

        beforeAll();
        tally = new Tally();

        // Queue all tests
        //runAttacks(new ItemStack[]{}, () -> {}); // with no armour
        testArmour();
        //testEnchantedMelee(new ItemStack[]{}, () -> {});

        // Run all tests in the queue
        runQueuedTests();
    }

    private void runAttacks(ItemStack[] armour, Runnable preparations) {
        //testMelee(armour, preparations);
        testEnchantedMelee(armour, preparations);
        testOverdamage(armour, preparations);
    }

    private void testArmour() {
        final String[] materials = {"LEATHER", "CHAINMAIL", "GOLDEN", "IRON", "DIAMOND", "NETHERITE"};
        final String[] slots = {"BOOTS", "LEGGINGS", "CHESTPLATE", "HELMET" };
        final Random random = new Random(System.currentTimeMillis());

        final ItemStack[] armourContents = new ItemStack[4];
        for (int i = 0; i < slots.length; i++) {
            final String slot = slots[i];
            // Pick a random material for each slot
            final String material = materials[random.nextInt(6)];

            final ItemStack itemStack = new ItemStack(Material.valueOf(material + "_" + slot));
            armourContents[i] = itemStack;
        }

        runAttacks(armourContents, () -> defender.getInventory().setArmorContents(armourContents));
    }

    private void testEnchantedMelee(ItemStack[] armour, Runnable preparations) {
        for (Material weaponType : WeaponDamages.getMaterialDamages().keySet()) {
            final ItemStack weapon = new ItemStack(weaponType);

            // only axe and sword can have sharpness
            /*
            try {
                weapon.addEnchantment(Enchantment.DAMAGE_ALL,3);}
            catch (IllegalArgumentException ignored){
            }
             */

            final String message = weaponType.name() + " Sharpness 3";
            queueAttack(new OCMTest(weapon, armour, 1, message, () -> {
                preparations.run();
                defender.setMaximumNoDamageTicks(0);
                //attacker.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE,10,0,false));
                attacker.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,10,0,false));
                Messenger.debug("TESTING WEAPON " + weaponType);
                //attacker.setFallDistance(2);
            }));
        }
    }

    private void testMelee(ItemStack[] armour, Runnable preparations) {
        for (Material weaponType : WeaponDamages.getMaterialDamages().keySet()) {
            final ItemStack weapon = new ItemStack(weaponType);
            queueAttack(new OCMTest(weapon, armour, 1, weaponType.name(), () -> {
                preparations.run();
                defender.setMaximumNoDamageTicks(0);
            }));
        }
    }

    private void testOverdamage(ItemStack[] armour, Runnable preparations) {
        // 1, 5, 6, 7, 3, 8 according to OCM
        // 1, 4, 5, 6, 2, 7 according to 1.9+
        Material[] weapons = {Material.WOODEN_HOE, Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD, Material.WOODEN_PICKAXE, Material.DIAMOND_SWORD};

        for (Material weaponType : weapons) {
            final ItemStack weapon = new ItemStack(weaponType);
            queueAttack(new OCMTest(weapon, armour, 3, weaponType.name(), () -> {
                preparations.run();
                defender.setMaximumNoDamageTicks(30);
            }));
        }
    }

    private void queueAttack(OCMTest test){
        testQueue.add(test);
    }

    private double calculateAttackDamage(ItemStack weapon){
        final Material weaponType = weapon.getType();
        // Attack components order: (Base + Potion effects, scaled by attack delay) + Critical Hit + (Enchantments, scaled by attack delay)
        // Hurt components order: Overdamage - Armour Effects
        double expectedDamage = WeaponDamages.getDamage(weaponType);

        // Weakness effect, 1.8: -0.5 * level
        final PotionEffect weakness = attacker.getPotionEffect(PotionEffectType.WEAKNESS);
        final double weaknessAddend = weakness != null ? (weakness.getAmplifier() + 1) * -0.5 : 0;

        // Strength effect
        // 1.8: +130% for each strength level
        final PotionEffect strength = attacker.getPotionEffect(PotionEffectType.INCREASE_DAMAGE);
        if(strength != null)
            expectedDamage += (strength.getAmplifier() + 1) * 1.3 * expectedDamage;

        expectedDamage += weaknessAddend;

        // Take into account damage reduction because of cooldown
        final float attackCooldown = defender.getAttackCooldown();
        expectedDamage *= 0.2F + attackCooldown * attackCooldown * 0.8F;

        // Critical hit
        if(attacker.getFallDistance() > 0){
            expectedDamage *= 1.5;
        }

        // Weapon Enchantments
        double sharpnessDamage = DamageUtils.getOldSharpnessDamage(weapon.getEnchantmentLevel(Enchantment.DAMAGE_ALL));
        sharpnessDamage *= attackCooldown; // Scale by attack cooldown strength
        expectedDamage += sharpnessDamage;

        return expectedDamage;
    }

    private boolean wasFakeOverdamage(ItemStack weapon) {
        double weaponDamage = calculateAttackDamage(weapon);
        final double lastDamage = defender.getLastDamage();
        return (float) defender.getNoDamageTicks() > (float) defender.getMaximumNoDamageTicks() / 2.0F &&
                weaponDamage <= lastDamage;
    }

    private boolean wasOverdamaged(double rawWeaponDamage) {
        final double lastDamage = defender.getLastDamage();
        return (float) defender.getNoDamageTicks() > (float) defender.getMaximumNoDamageTicks() / 2.0F &&
                rawWeaponDamage > lastDamage;
    }

    private float calculateExpectedDamage(ItemStack weapon, ItemStack[] armourContents) {
        double expectedDamage = calculateAttackDamage(weapon);

        // Overdamage
        if (wasOverdamaged(expectedDamage)) {
            double lastDamage = defender.getLastDamage();
            Messenger.sendNormalMessage(sender, "Overdamaged: " + expectedDamage + " - " + lastDamage + " = " + (expectedDamage - lastDamage));
            expectedDamage -= lastDamage;
        }

        // Armour effects (1.8, with OldArmourStrength module)
        expectedDamage = ArmourUtils.getDamageAfterArmour1_8(expectedDamage, armourContents, EntityDamageEvent.DamageCause.ENTITY_ATTACK);

        return (float) expectedDamage;
    }

    private void runQueuedTests() {
        Messenger.sendNormalMessage(sender, "Running " + testQueue.size() + " tests");

        // Listener gets called every time defender is damaged
        Listener listener = new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onEvent(EntityDamageByEntityEvent e) {
                final Entity damager = e.getDamager();
                if (damager.getUniqueId() != attacker.getUniqueId() ||
                        e.getEntity().getUniqueId() != defender.getUniqueId()) return;

                final ItemStack weapon = ((Player) damager).getInventory().getItemInMainHand();
                final Material weaponType = weapon.getType();
                OCMTest test = testQueue.remove();
                ItemStack expectedWeapon = test.weapon;

                // Comparison is overriden for itemstacks, checks all properties
                while(weaponType != expectedWeapon.getType()){ // One of the attacks dealt no damage
                    Messenger.sendNormalMessage(sender, "&bSKIPPED &f" + expectedWeapon.getType() + " &fNo damage");
                    //TODO only if is expected damage 0, it's passed
                    tally.passed();
                    test = testQueue.remove();
                    expectedWeapon = test.weapon;
                }

                final float expectedDamage = calculateExpectedDamage(expectedWeapon, test.armour);

                if (wasFakeOverdamage(weapon) && e.isCancelled()) {
                    Messenger.sendNormalMessage(sender, "&aPASSED &fFake overdamage " + expectedDamage + " < " + ((LivingEntity) e.getEntity()).getLastDamage());
                    tally.passed();
                } else {
                    final String weaponMessage = "E: " + expectedWeapon.getType().name() + " A: " + weaponType.name();
                    TesterUtils.assertEquals(expectedDamage, (float) e.getFinalDamage(), tally, weaponMessage, sender);
                }
            }
        };

        Bukkit.getServer().getPluginManager().registerEvents(listener, ocm);

        final long testCount = testQueue.size();

        // Cumulative attack delay for scheduling all the tests
        long attackDelay = 0;

        for (OCMTest test : testQueue) {
            attackDelay += test.attackDelay;

            Bukkit.getScheduler().runTaskLater(ocm, () -> {
                beforeEach();
                test.preparations.run();
                preparePlayer(test.weapon);
                attacker.attack(defender);
                afterEach();
            }, attackDelay);

        }

        Bukkit.getScheduler().runTaskLater(ocm, () -> {
            afterAll(testCount);
            EntityDamageByEntityEvent.getHandlerList().unregister(listener);
        }, attackDelay + 1);
    }

    private void beforeAll() {
        for (Player player : new Player[]{attacker, defender}) {
            player.setGameMode(GameMode.SURVIVAL);
            player.setMaximumNoDamageTicks(20);
            player.setNoDamageTicks(0); // remove spawn invulnerability
            player.setInvulnerable(false);
        }
    }

    private void afterAll(long testCount) {
        fakeAttacker.removePlayer();
        fakeDefender.removePlayer();

        final long missed = testCount - tally.getTotal();
        Messenger.send(sender, "Passed: &a%d &rFailed: &c%d &rTotal: &7%d &rMissed: &7%d", tally.getPassed(), tally.getFailed(), tally.getTotal(), missed);
    }

    private void beforeEach() {
        for (Player player : new Player[]{attacker, defender}) {
            player.getInventory().clear();
            player.setExhaustion(0);
            player.setHealth(20);
        }
    }

    private void preparePlayer(ItemStack weapon){
        if (weapon.hasItemMeta()) {
            final ItemMeta meta = weapon.getItemMeta();
            meta.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED,
                    new AttributeModifier(UUID.randomUUID(), "speed", 1000,
                            AttributeModifier.Operation.ADD_NUMBER, EquipmentSlot.HAND));
            weapon.setItemMeta(meta);
        }
        attacker.getInventory().setItemInMainHand(weapon);
        attacker.updateInventory();

        // Update attack attribute cause it won't get done with fake players
        final AttributeInstance ai = attacker.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        final AttributeInstance defenderArmour = defender.getAttribute(Attribute.GENERIC_ARMOR);

        weapon.getType().getDefaultAttributeModifiers(EquipmentSlot.HAND).get(Attribute.GENERIC_ATTACK_DAMAGE).forEach(am -> {
            ai.removeModifier(am);
            ai.addModifier(am);
        });

        // Update armour attribute
        final ItemStack[] armourContents = defender.getInventory().getArmorContents();
        Messenger.debug("Armour: " + Arrays.stream(armourContents).filter(Objects::nonNull).map(is -> is.getType().name()).reduce((a, b) -> a + ", " + b).orElse("none"));
        for (int i = 0; i < armourContents.length; i++) {
            final ItemStack itemStack = armourContents[i];
            if (itemStack == null) continue;
            final Material type = itemStack.getType();
            final EquipmentSlot slot = new EquipmentSlot[]{EquipmentSlot.FEET,EquipmentSlot.LEGS,EquipmentSlot.CHEST,EquipmentSlot.HEAD}[i];
            for (AttributeModifier attributeModifier : type.getDefaultAttributeModifiers(slot).get(Attribute.GENERIC_ARMOR)) {
                defenderArmour.removeModifier(attributeModifier);
                defenderArmour.addModifier(attributeModifier);
            }
        }

    }

    private void afterEach() {
        for (Player player : new Player[]{attacker, defender}) {
            player.setExhaustion(0);
            player.setHealth(20);
        }
    }
}
