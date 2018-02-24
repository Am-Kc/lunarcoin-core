package io.lunarchain.lunarcoin.util.zksnark

import io.lunarchain.lunarcoin.util.zksnark.Params.B_Fp2
import io.lunarchain.lunarcoin.util.zksnark.Params.PAIRING_FINAL_EXPONENT_Z
import io.lunarchain.lunarcoin.util.zksnark.Params.TWIST
import java.math.BigInteger
import java.util.ArrayList

class PairingCheck() {

    private var pairs: MutableList<Pair> = ArrayList()
    private var product = Fp12._1
    
    companion object {

        internal val LOOP_COUNT = BigInteger("29793968203157093288")


        fun create(): PairingCheck {
            return PairingCheck()
        }

        private fun millerLoop(g1: BN128G1, g2: BN128G2): Fp12 {
            var g1 = g1
            var g2 = g2

            // convert to affine coordinates
            g1 = g1.toAffine()
            g2 = g2.toAffine()

            // calculate Ell coefficients
            val coeffs = calcEllCoeffs(g2)

            var f = Fp12._1
            var idx = 0

            // for each bit except most significant one
            for (i in LOOP_COUNT.bitLength() - 2 downTo 0) {

                var c = coeffs[idx++]
                f = f.squared()
                f = f.mulBy024(c.ell0, g1.y()!!.mul(c.ellVW), g1.x()!!.mul(c.ellVV))

                if (LOOP_COUNT.testBit(i)) {
                    c = coeffs[idx++]
                    f = f.mulBy024(c.ell0, g1.y()!!.mul(c.ellVW), g1.x()!!.mul(c.ellVV))
                }

            }

            var c = coeffs[idx++]
            f = f.mulBy024(c.ell0, g1.y()!!.mul(c.ellVW), g1.x()!!.mul(c.ellVV))

            c = coeffs[idx]
            f = f.mulBy024(c.ell0, g1.y()!!.mul(c.ellVW), g1.x()!!.mul(c.ellVV))

            return f
        }


        private fun calcEllCoeffs(base: BN128G2): List<EllCoeffs> {

            val coeffs = ArrayList<EllCoeffs>()

            var addend = base

            // for each bit except most significant one
            for (i in LOOP_COUNT.bitLength() - 2 downTo 0) {

                val doubling = flippedMillerLoopDoubling(addend)

                addend = doubling.g2
                coeffs.add(doubling.coeffs)

                if (LOOP_COUNT.testBit(i)) {
                    val addition = flippedMillerLoopMixedAddition(base, addend)
                    addend = addition.g2
                    coeffs.add(addition.coeffs)
                }
            }

            val q1 = base.mulByP()
            var q2 = q1.mulByP()

            q2 = BN128G2(q2.x(), q2.y()!!.negate(), q2.z()) // q2.y = -q2.y

            var addition = flippedMillerLoopMixedAddition(q1, addend)
            addend = addition.g2
            coeffs.add(addition.coeffs)

            addition = flippedMillerLoopMixedAddition(q2, addend)
            coeffs.add(addition.coeffs)

            return coeffs
        }

        private fun flippedMillerLoopMixedAddition(base: BN128G2, addend: BN128G2): Precomputed {

            val x1 = addend.x()
            val y1 = addend.y()
            val z1 = addend.z()
            val x2 = base.x()
            val y2 = base.y()

            val d = x1!!.sub(x2!!.mul(z1))             // d = x1 - x2 * z1
            val e = y1!!.sub(y2!!.mul(z1))             // e = y1 - y2 * z1
            val f = d.squared()                    // f = d^2
            val g = e.squared()                    // g = e^2
            val h = d.mul(f)                       // h = d * f
            val i = x1.mul(f)                      // i = x1 * f
            val j = h.add(z1!!.mul(g)).sub(i.dbl())  // j = h + z1 * g - 2 * i

            val x3 = d.mul(j)                           // x3 = d * j
            val y3 = e.mul(i.sub(j)).sub(h.mul(y1))     // y3 = e * (i - j) - h * y1)
            val z3 = z1.mul(h)                          // z3 = Z1*H

            val ell0 = TWIST.mul(e.mul(x2).sub(d.mul(y2)))     // ell_0 = TWIST * (e * x2 - d * y2)
            val ellVV = e.negate()                             // ell_VV = -e

            return Precomputed.of(
                BN128G2(x3, y3, z3),
                EllCoeffs(ell0, d, ellVV)
            )
        }

        fun finalExponentiation(el: Fp12): Fp12 {
            // first chunk
            val w = Fp12(el.a, el.b.negate()) // el.b = -el.b
            val x = el.inverse()
            val y = w.mul(x)
            val z = y.frobeniusMap(2)
            val pre = z.mul(y)
            // last chunk
            val a = pre.negExp(PAIRING_FINAL_EXPONENT_Z)
            val b = a.cyclotomicSquared()
            val c = b.cyclotomicSquared()
            val d = c.mul(b)
            val e = d.negExp(PAIRING_FINAL_EXPONENT_Z)
            val f = e.cyclotomicSquared()
            val g = f.negExp(PAIRING_FINAL_EXPONENT_Z)
            val h = d.unitaryInverse()
            val i = g.unitaryInverse()
            val j = i.mul(e)
            val k = j.mul(h)
            val l = k.mul(b)
            val m = k.mul(e)
            val n = m.mul(pre)
            val o = l.frobeniusMap(1)
            val p = o.mul(n)
            val q = k.frobeniusMap(2)
            val r = q.mul(p)
            val s = pre.unitaryInverse()
            val t = s.mul(l)
            val u = t.frobeniusMap(3)

            return u.mul(r)

        }

        private fun flippedMillerLoopDoubling(g2: BN128G2): Precomputed {

            val x = g2!!.x()
            val y = g2.y()
            val z = g2.z()

            val a = Fp._2_INV.mul(x!!.mul(y))            // a = x * y / 2
            val b = y!!.squared()                        // b = y^2
            val c = z!!.squared()                        // c = z^2
            val d = c.add(c).add(c)                    // d = 3 * c
            val e = B_Fp2.mul(d)                       // e = twist_b * d
            val f = e.add(e).add(e)                    // f = 3 * e
            val g = Fp._2_INV.mul(b.add(f))            // g = (b + f) / 2
            val h = y.add(z).squared().sub(b.add(c))   // h = (y + z)^2 - (b + c)
            val i = e.sub(b)                           // i = e - b
            val j = x!!.squared()                        // j = x^2
            val e2 = e.squared()                       // e2 = e^2

            val rx = a.mul(b.sub(f))                       // rx = a * (b - f)
            val ry = g.squared().sub(e2.add(e2).add(e2))   // ry = g^2 - 3 * e^2
            val rz = b.mul(h)                              // rz = b * h

            val ell0 = TWIST.mul(i)        // ell_0 = twist * i
            val ellVW = h.negate()         // ell_VW = -h
            val ellVV = j.add(j).add(j)    // ell_VV = 3 * j

            return Precomputed.of(
                BN128G2(rx, ry, rz),
                EllCoeffs(ell0, ellVW, ellVV)
            )
        }
        internal class Precomputed(var g2: BN128G2, var coeffs: EllCoeffs) {
            companion object {

                fun of(g2: BN128G2, coeffs: EllCoeffs): Precomputed {
                    return Precomputed(g2, coeffs)
                }
            }
        }

        internal class Pair(var g1: BN128G1, var g2: BN128G2) {

            fun millerLoop(): Fp12 {

                // miller loop result equals "1" if at least one of the points is zero
                if (g1.isZero()) return Fp12._1
                return if (g2.isZero()) Fp12._1 else millerLoop(g1, g2)

            }

            companion object {

                fun of(g1: BN128G1, g2: BN128G2): Pair {
                    return Pair(g1, g2)
                }
            }
        }

        internal class EllCoeffs(var ell0: Fp2, var ellVW: Fp2, var ellVV: Fp2)

    }

    fun result(): Int {
        return if (product.equals(Fp12._1)) 1 else 0
    }

    fun addPair(g1: BN128G1, g2: BN128G2) {
        pairs.add(Pair(g1, g2))
    }

    fun run() {

        for (pair in pairs) {

            val miller = pair.millerLoop()

            if (!miller.equals(Fp12._1))
            // run mul code only if necessary
                product = product.mul(miller)
        }

        // finalize
        product = finalExponentiation(product)
    }




}