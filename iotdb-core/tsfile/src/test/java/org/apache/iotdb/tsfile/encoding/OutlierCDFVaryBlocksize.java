package org.apache.iotdb.tsfile.encoding;

import com.csvreader.CsvReader;
import com.csvreader.CsvWriter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Stack;

import static java.lang.Math.pow;

public class OutlierCDFVaryBlocksize {

    public static int getBitWith(int num) {
        if (num == 0) return 1;
        else return 32 - Integer.numberOfLeadingZeros(num);
    }

    public static byte[] int2Bytes(int integer) {
        byte[] bytes = new byte[4];
        bytes[0] = (byte) (integer >> 24);
        bytes[1] = (byte) (integer >> 16);
        bytes[2] = (byte) (integer >> 8);
        bytes[3] = (byte) integer;
        return bytes;
    }

    public static byte[] intByte2Bytes(int integer) {
        byte[] bytes = new byte[1];
        bytes[0] = (byte) integer;
        return bytes;
    }

    public static byte[] double2Bytes(double dou) {
        long value = Double.doubleToRawLongBits(dou);
        byte[] bytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            bytes[i] = (byte) ((value >> 8 * i) & 0xff);
        }
        return bytes;
    }

    public static double bytes2Double(ArrayList<Byte> encoded, int start, int num) {
        if (num > 8) {
            System.out.println("bytes2Doubleerror");
            return 0;
        }
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= ((long) (encoded.get(i + start) & 0xff)) << (8 * i);
        }
        return Double.longBitsToDouble(value);
    }

    public static byte[] float2bytes(float f) {
        int fbit = Float.floatToIntBits(f);
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            b[i] = (byte) (fbit >> (24 - i * 8));
        }
        int len = b.length;
        byte[] dest = new byte[len];
        System.arraycopy(b, 0, dest, 0, len);
        byte temp;
        for (int i = 0; i < len / 2; ++i) {
            temp = dest[i];
            dest[i] = dest[len - i - 1];
            dest[len - i - 1] = temp;
        }
        return dest;
    }

    public static float bytes2float(ArrayList<Byte> b, int index) {
        int l;
        l = b.get(index);
        l &= 0xff;
        l |= ((long) b.get(index + 1) << 8);
        l &= 0xffff;
        l |= ((long) b.get(index + 2) << 16);
        l &= 0xffffff;
        l |= ((long) b.get(index + 3) << 24);
        return Float.intBitsToFloat(l);
    }

    public static int bytes2Integer(ArrayList<Byte> encoded, int start, int num) {
        int value = 0;
        if (num > 4) {
            System.out.println("bytes2Integer error");
            return 0;
        }
        for (int i = 0; i < num; i++) {
            value <<= 8;
            int b = encoded.get(i + start) & 0xFF;
            value |= b;
        }
        return value;
    }

    public static byte[] bitPacking(ArrayList<Integer> numbers, int start, int bit_width) {
        int block_num = numbers.size() / 8;
        byte[] result = new byte[bit_width * block_num];
        for (int i = 0; i < block_num; i++) {
            for (int j = 0; j < bit_width; j++) {
                int tmp_int = 0;
                for (int k = 0; k < 8; k++) {
                    tmp_int += (((numbers.get(i * 8 + k + start) >> j) % 2) << k);
                }
                result[i * bit_width + j] = (byte) tmp_int;
            }
        }
        return result;
    }

    public static byte[] bitPacking(ArrayList<ArrayList<Integer>> numbers, int start, int index, int bit_width) {
        int block_num = numbers.size() / 8;
        byte[] result = new byte[bit_width * block_num];
        for (int i = 0; i < block_num; i++) {
            for (int j = 0; j < bit_width; j++) {
                int tmp_int = 0;
                for (int k = 0; k < 8; k++) {
                    tmp_int += (((numbers.get(i * 8 + k + start).get(index) >> j) % 2) << k);
                }
                result[i * bit_width + j] = (byte) tmp_int;
            }
        }
        return result;
    }

    public static ArrayList<Integer> decodebitPacking(
            ArrayList<Byte> encoded, int decode_pos, int bit_width, int min_delta, int block_size) {
        ArrayList<Integer> result_list = new ArrayList<>();
        for (int i = 0; i < (block_size - 1) / 8; i++) { // bitpacking  纵向8个，bit width是多少列
            int[] val8 = new int[8];
            for (int j = 0; j < 8; j++) {
                val8[j] = 0;
            }
            for (int j = 0; j < bit_width; j++) {
                byte tmp_byte = encoded.get(decode_pos + bit_width - 1 - j);
                byte[] bit8 = new byte[8];
                for (int k = 0; k < 8; k++) {
                    bit8[k] = (byte) (tmp_byte & 1);
                    tmp_byte = (byte) (tmp_byte >> 1);
                }
                for (int k = 0; k < 8; k++) {
                    val8[k] = val8[k] * 2 + bit8[k];
                }
            }
            for (int j = 0; j < 8; j++) {
                result_list.add(val8[j] + min_delta);
            }
            decode_pos += bit_width;
        }
        return result_list;
    }

    public static int part(ArrayList<Integer> arr, int low, int high) {
        int tmp = arr.get(low);
        while (low < high) {
            while (low < high && (arr.get(high) > tmp)) {
                high--;
            }
            arr.set(low, arr.get(high));
            while (low < high && (arr.get(low) < tmp)) {
                low++;
            }
            arr.set(high, arr.get(low));
        }
        arr.set(low, tmp);
        return low;
    }

    public static void quickSort(ArrayList<Integer> arr, int index, int low, int high) {
        Stack<Integer> stack = new Stack<>();
        while (!stack.empty()) {
            high = stack.pop();
            low = stack.pop();
            int mid = part(arr, low, high);

            if (mid + 1 < high) {
                stack.push(mid + 1);
                stack.push(high);
            }
            if (mid - 1 > low) {
                stack.push(low);
                stack.push(mid - 1);
            }
        }
    }

    public static int getCommon(int m, int n) {
        int z;
        while (m % n != 0) {
            z = m % n;
            m = n;
            n = z;
        }
        return n;
    }

    public static void splitTimeStamp3(
            ArrayList<Integer> ts_block, ArrayList<Integer> result) {
        int td_common = 0;
        for (int i = 1; i < ts_block.size(); i++) {
            int time_diffi = ts_block.get(i) - ts_block.get(i - 1);
            if (td_common == 0) {
                if (time_diffi != 0) {
                    td_common = time_diffi;
                    continue;
                } else {
                    continue;
                }
            }
            if (time_diffi != 0) {
                td_common = getCommon(time_diffi, td_common);
                if (td_common == 1) {
                    break;
                }
            }
        }
        if (td_common == 0) {
            td_common = 1;
        }

        int t0 = ts_block.get(0);
        for (int i = 0; i < ts_block.size(); i++) {
            int interval_i = (ts_block.get(i) - t0) / td_common;
            ts_block.set(i, t0 + interval_i);
        }
        result.add(td_common);
    }


    public static ArrayList<Integer> getAbsDeltaTsBlock(
            ArrayList<Integer> ts_block,
            ArrayList<Integer> min_delta) {
        ArrayList<Integer> ts_block_delta = new ArrayList<>();

//        ts_block_delta.add(ts_block.get(0));
        int value_delta_min = Integer.MAX_VALUE;
        for (int i = 1; i < ts_block.size(); i++) {

            int epsilon_v = ts_block.get(i) - ts_block.get(i - 1);

            if (epsilon_v < value_delta_min) {
                value_delta_min = epsilon_v;
            }

        }
        min_delta.add(ts_block.get(0));
        min_delta.add(value_delta_min);

        for (int i = 1; i < ts_block.size(); i++) {
            int epsilon_v = ts_block.get(i) - value_delta_min - ts_block.get(i - 1);
            ts_block_delta.add(epsilon_v);
        }
        return ts_block_delta;
    }

    public static ArrayList<Integer> getBitWith(ArrayList<Integer> ts_block) {
        ArrayList<Integer> ts_block_bit_width = new ArrayList<>();
        for (int integers : ts_block) {
            ts_block_bit_width.add(getBitWith(integers));
        }
        return ts_block_bit_width;
    }

    public static ArrayList<Byte> encode2Bytes(
            ArrayList<Integer> ts_block,
            ArrayList<Integer>  min_delta,
            int bit_width) {
        ArrayList<Byte> encoded_result = new ArrayList<>();

        // encode value0
        byte[] value0_byte = int2Bytes(min_delta.get(0));
        for (byte b : value0_byte) encoded_result.add(b);

        // encode theta
        byte[] value_min_byte = int2Bytes(min_delta.get(1));
        for (byte b : value_min_byte) encoded_result.add(b);

        // encode value
        byte[] max_bit_width_value_byte = int2Bytes(bit_width);
        for (byte b : max_bit_width_value_byte) encoded_result.add(b);
        byte[] value_bytes = bitPacking(ts_block, 0, bit_width);
        for (byte b : value_bytes) encoded_result.add(b);


        return encoded_result;
    }

    public static ArrayList<Byte> encodeOutlier2Bytes(
            ArrayList<Integer> ts_block_delta,
            int bit_width) {
        ArrayList<Byte> encoded_result = new ArrayList<>();
//         encode value
        byte[] value_bytes = bitPacking(ts_block_delta, 0, bit_width);
        for (byte b : value_bytes) encoded_result.add(b);

        int n_k = ts_block_delta.size();
        int n_k_b = n_k / 8;
        int remaining = n_k - n_k_b * 8;

        int cur_remaining = 0;
        int cur_number_bits = 0;
        for (int i = n_k_b * 8; i < n_k; i++) {
            int cur_value = ts_block_delta.get(i);
            int cur_bit_width = bit_width;
            if (cur_number_bits + bit_width > 32) {
                cur_remaining <<= (32 - cur_number_bits);
                cur_bit_width = bit_width - 32 + cur_number_bits;
                cur_remaining += (cur_value >> cur_bit_width);

                byte[] cur_remaining_byte = int2Bytes(cur_remaining);
                for (byte b : cur_remaining_byte) encoded_result.add(b);
                cur_remaining = 0;
            }
            cur_remaining <<= cur_bit_width;
            cur_remaining += ((cur_value << (32 - cur_bit_width)) >> (32 - cur_bit_width));
        }
        byte[] cur_remaining_byte = int2Bytes(cur_remaining);
        for (byte b : cur_remaining_byte) encoded_result.add(b);


        return encoded_result;
    }

    private static ArrayList<Byte> learnKDelta(ArrayList<Integer> ts_block, int supple_length) {

        int block_size = ts_block.size()-1;
        ArrayList<Byte> cur_byte = new ArrayList<>();

        ArrayList<Integer> min_delta = new ArrayList<>();
        ArrayList<Integer> ts_block_delta = getAbsDeltaTsBlock(ts_block, min_delta);
        ArrayList<Integer> min_delta_r = new ArrayList<>();
        ArrayList<Integer> ts_block_order_value = getAbsDeltaTsBlock(ts_block, min_delta_r);
        for (int s = 0; s < supple_length; s++) {
            ts_block_delta.add(0);
            ts_block_order_value.add(0);
        }
//        System.out.println(ts_block_order_value);
        Collections.sort(ts_block_order_value);
//        quickSort(ts_block_order_value, 0, 1, block_size - 1);

//        System.out.println("quickSort");
        int bit_width = getBitWith(ts_block_delta.get(block_size - 1));

//        ArrayList<Integer> ts_block_order_value = new ArrayList<>();
//        for (int i = 1; i < block_size; i++) {
//            ts_block_order_value.add(ts_block_delta.get(i));
//        }


        int max_delta_value = ts_block_order_value.get(block_size - 1);
        int max_delta_value_bit_width = getBitWith(max_delta_value);
        ArrayList<Integer> spread_value = new ArrayList<>();
        for (int i = 1; i < max_delta_value_bit_width; i++) {
            int spread_v = (int) pow(2, i) - 1;
            spread_value.add(spread_v);
        }
        ArrayList<ArrayList<Integer>> PDF = new ArrayList<>();


        ArrayList<Integer> start_value = new ArrayList<>();
        int min_delta_value = ts_block_order_value.get(0);
        start_value.add(min_delta_value);

        ArrayList<Integer> tmp_value = new ArrayList<>();
        tmp_value.add(min_delta_value);
        int count = 1;
        int tmp = min_delta_value;
        for (int i = 1; i < block_size; i++) {
            if (ts_block_order_value.get(i) != tmp) {
                int start_v = ts_block_order_value.get(i);
                tmp_value.add(count);
                PDF.add(tmp_value);
                tmp_value = new ArrayList<>();
                start_value.add(start_v);
                tmp_value.add(start_v);
                tmp = start_v;
//                count = 1;
            }
            count++;

        }
        tmp_value.add(count);
        PDF.add(tmp_value);
//        System.out.println(PDF);

        int final_k_start_value = ts_block_order_value.get(0);
        int final_k_end_value = max_delta_value;

        int min_bits = 0;
        min_bits += (getBitWith(final_k_end_value - final_k_start_value) * (block_size - 1));


        int start_value_size = start_value.size();
        int k1 = 0;
        int k2 = 0;
        int final_left_max = 0;
        int final_right_min = max_delta_value;
//        System.out.println(PDF);
        for (int start_value_i = 0; start_value_i < start_value_size-1; start_value_i++) {
            int k_start_value = start_value.get(start_value_i);
//            for (int end_value_i = start_value_i; end_value_i < start_value_size; end_value_i++) {
//                int k_end_value = start_value.get(end_value_i);
            for (int k_spread_value : spread_value) {
                int k_end_value = Math.min(k_spread_value + k_start_value, max_delta_value);

                int cur_bits = 0;
                int cur_k1 = 0;
                int left_max = 0;
                if (start_value_i != 0) {
                    left_max = PDF.get(start_value_i - 1).get(0);
                    cur_k1 = PDF.get(start_value_i - 1).get(1);
                }

                int cur_k2 = 0;
                int max_normal = 0;
                int min_upper_outlier = 0;
                int PDF_size = PDF.size();
                for (int tmp_j = start_value_i; tmp_j < PDF_size; tmp_j++) {
                    max_normal = PDF.get(tmp_j).get(0);
                    if (max_normal >= k_end_value) {
                        if(max_normal > k_end_value)
                            cur_k2 = block_size - PDF.get(tmp_j-1).get(1);
                        else if (max_normal == k_end_value) {
                            cur_k2 = block_size - PDF.get(tmp_j).get(1);
                        }
                        if (tmp_j != PDF_size - 1)
                            min_upper_outlier = PDF.get(tmp_j + 1).get(0);
                        else
                            min_upper_outlier = PDF.get(tmp_j).get(0);
                        break;
                    }
                }

                cur_bits += Math.min((cur_k1 + cur_k2) * getBitWith(block_size), block_size + cur_k1 + cur_k2);
                if (cur_k1 != 0)
                    cur_bits += cur_k1 * getBitWith(left_max);//left_max
                if (cur_k1 + cur_k2 != block_size)
                    cur_bits += (block_size - cur_k1 - cur_k2) * getBitWith(k_end_value - k_start_value);
                if (cur_k2 != 0)
                    cur_bits += cur_k2 * getBitWith(max_delta_value - min_upper_outlier);//min_upper_outlier
//
//                if(left_max <= 1603 && k_start_value>=1603&& k_end_value <= 5083 && min_upper_outlier>=5083 ){
//                    System.out.println("index_cost: "+(Math.min((cur_k1 + cur_k2) * getBitWith(block_size), block_size + cur_k1 + cur_k2)));
//                    System.out.println("k_end_value: "+(k_end_value));
//                    System.out.println("min_upper_outlier: "+(min_upper_outlier));
//                    System.out.println("cur_k1: "+(cur_k1));
//                    System.out.println("cur_k2: "+(cur_k2));
//                    System.out.println("cur_bits: "+(cur_bits));
//                    System.out.println("min_bits: "+(min_bits));
//                    System.out.println("min_bits: "+(getBitWith(k_start_value)));
//                    System.out.println("min_bits: "+(getBitWith(k_end_value - k_start_value)));
//                    System.out.println("min_bits: "+(getBitWith(max_delta_value - k_end_value)));
//                }
//                if(k_start_value == 49 && k_end_value == 50 ){
//                    System.out.println("index_cost: "+(Math.min((cur_k1 + cur_k2) * getBitWith(block_size), block_size + cur_k1 + cur_k2)));
//                    System.out.println("max_delta_value: "+(max_delta_value));
//                    System.out.println(" getBitWith(left_max)：" +  getBitWith(left_max));
//                    System.out.println(" getBitWith(k_end_value - k_start_value)：" + getBitWith(k_end_value - k_start_value));
//                    System.out.println(" getBitWith(max_delta_value - min_upper_outlier)：" + getBitWith(max_delta_value - min_upper_outlier));
//                    System.out.println("k_start_value: "+(k_start_value));
//                    System.out.println("k_end_value: "+(k_end_value));
//                    System.out.println("left_max: "+(left_max));
//                    System.out.println("min_upper_outlier: "+(min_upper_outlier));
//                    System.out.println("cur_k1: "+(cur_k1));
//                    System.out.println("cur_k2: "+(cur_k2));
//                    System.out.println("cur_bits: "+(cur_bits));
//                    System.out.println("min_bits: "+(min_bits));
//                }

                if (cur_bits < min_bits) {
                    min_bits = cur_bits;
                    k1 = cur_k1;
                    k2 = cur_k2;
                    final_k_start_value = k_start_value;
                    if (start_value_i != 0)
                        final_left_max = PDF.get(start_value_i - 1).get(0);
                    else
                        final_left_max = 0;
                    final_right_min = min_upper_outlier;
                    final_k_end_value = k_end_value;
                }
                if (k_end_value == max_delta_value)
                    break;
            }
        }
//        System.out.println("final_k_start_value: "+(final_k_start_value));
//        System.out.println("final_k_end_value: "+(final_k_end_value));

//        final_k_start_value = 1603;
//        final_k_end_value = 5087;


//        int final_left_max = final_k_start_value;
        int final_alpha = ((k1 + k2) * getBitWith(block_size)) <= (block_size + k1 + k2) ? 1 : 0;
//        final_alpha = 1;

        // ------------------------- encode data -----------------------------------------
        if (final_left_max == 0 && final_k_end_value == max_delta_value) {
            cur_byte = encode2Bytes(ts_block_delta, min_delta, bit_width);
        }
        else {
            ArrayList<Integer> final_left_outlier_index = new ArrayList<>();
            ArrayList<Integer> final_right_outlier_index = new ArrayList<>();
            ArrayList<Integer> final_left_outlier = new ArrayList<>();
            ArrayList<Integer> final_right_outlier = new ArrayList<>();
            ArrayList<Integer> final_normal = new ArrayList<>();
            int cur_k = 0;
            int cur_k1 = 0;
            ArrayList<Integer> bitmap = new ArrayList<>();
            ArrayList<Integer> bitmap_outlier = new ArrayList<>();
            int index_bitmap = 0;
            int index_bitmap_outlier = 0;
            int cur_index_bitmap_outlier_bits = 0;
            for (int i = 0; i < block_size; i++) {
                if (ts_block_delta.get(i) < final_k_start_value) {
                    final_left_outlier.add(ts_block_delta.get(i));
                    final_left_outlier_index.add(i);
                    if(cur_index_bitmap_outlier_bits%8!=7){
                        index_bitmap_outlier += 3;
                        index_bitmap_outlier <<= 2;
                    }else{
                        index_bitmap_outlier += 1;
                        bitmap_outlier.add(index_bitmap_outlier);
                        index_bitmap_outlier = 1;
                        index_bitmap_outlier <<= 1;
                    }
                    cur_index_bitmap_outlier_bits += 2;
//                    index_bitmap += 1;

                    cur_k1++;
                    cur_k++;

                } else if (ts_block_delta.get(i) > final_k_end_value) {
                    final_right_outlier.add(ts_block_delta.get(i) - final_k_end_value);
                    final_right_outlier_index.add(i);
                    if(cur_index_bitmap_outlier_bits%8!=7){
                        index_bitmap_outlier += 2;
                        index_bitmap_outlier <<= 2;
                    }else{
                        index_bitmap_outlier += 1;
                        bitmap_outlier.add(index_bitmap_outlier);
                        index_bitmap_outlier = 0;
                        index_bitmap_outlier <<= 1;
                    }
//                    index_bitmap += 1;
                    cur_k++;
                    cur_index_bitmap_outlier_bits += 2;
                } else {
                    final_normal.add(ts_block_delta.get(i) - final_k_start_value);
                    index_bitmap_outlier <<= 1;
                    cur_index_bitmap_outlier_bits += 1;
                }
                if (cur_index_bitmap_outlier_bits % 8 == 0) {
                    bitmap_outlier.add(index_bitmap_outlier);
                    index_bitmap_outlier = 0;
                }
            }
            if (cur_index_bitmap_outlier_bits % 8 != 0) {
                bitmap_outlier.add(index_bitmap_outlier);
            }

            int k_byte = (k1 << 1);
            k_byte += final_alpha;
            k_byte += (k2 << 16);

            byte[] k_bytes = int2Bytes(k_byte);
            for (byte b : k_bytes) cur_byte.add(b);


            byte[] value0_bytes = int2Bytes(min_delta.get(0));
            for (byte b : value0_bytes) cur_byte.add(b);
            byte[] min_delta_bytes = int2Bytes(min_delta.get(1));
            for (byte b : min_delta_bytes) cur_byte.add(b);

            byte[] final_k_start_value_bytes = int2Bytes(final_k_start_value);
            for (byte b : final_k_start_value_bytes) cur_byte.add(b);

            int bit_width_final = getBitWith(final_k_end_value - final_k_start_value);
            byte[] bit_width_bytes = intByte2Bytes(bit_width_final);
            for (byte b : bit_width_bytes) cur_byte.add(b);

            int left_bit_width = getBitWith(final_left_max);//final_left_max
            int right_bit_width = getBitWith(max_delta_value - final_right_min);//final_right_min
            bit_width_bytes = intByte2Bytes(left_bit_width);
            for (byte b : bit_width_bytes) cur_byte.add(b);
            bit_width_bytes = intByte2Bytes(right_bit_width);
            for (byte b : bit_width_bytes) cur_byte.add(b);

//            System.out.println(cur_byte.size());
//            System.out.println("bitmap size:"+bitmap.size());
//            System.out.println("bitmap_outlier size:"+bitmap_outlier.size());

            if (final_alpha == 0) {
//                for (int i : bitmap) {
//                    byte[] index_bytes = intByte2Bytes(i);
//                    for (byte b : index_bytes) cur_byte.add(b);
//                }
                for (int i : bitmap_outlier) {
                    byte[] index_bytes = intByte2Bytes(i);
                    for (byte b : index_bytes) cur_byte.add(b);
                }
            } else {
                cur_byte.addAll(encodeOutlier2Bytes(final_left_outlier_index, getBitWith(block_size)));
                cur_byte.addAll(encodeOutlier2Bytes(final_right_outlier_index, getBitWith(block_size)));
            }

//            System.out.println("n-k1-k2: " + (final_normal.size()));
//            System.out.println(bit_width_final);
//            System.out.println("k1:" + cur_k1);
//            System.out.println(left_bit_width);
//            System.out.println("k2:" + (cur_k-cur_k1));
//            System.out.println(right_bit_width);
//            System.out.println(cur_byte.size());
//
//            int cur_bits = 0;
//            cur_bits +=  (Math.min((cur_k) * getBitWith(block_size), block_size + cur_k));
//            System.out.println("cur_bits:     " + cur_bits);
//            cur_bits += cur_k1 * left_bit_width;//left_max
//            cur_bits += (block_size - cur_k ) * bit_width_final;
//            cur_bits += (cur_k- cur_k1) * right_bit_width;//left_max
//            System.out.println("cur_bits:     " + cur_bits);

            cur_byte.addAll(encodeOutlier2Bytes(final_normal, bit_width_final));
            if (k1 != 0)
                cur_byte.addAll(encodeOutlier2Bytes(final_left_outlier, left_bit_width));
            if (k2 != 0)
                cur_byte.addAll(encodeOutlier2Bytes(final_right_outlier, right_bit_width));
        }

//        System.out.println(cur_byte.size());
        return cur_byte;
    }

    public static ArrayList<Byte> ReorderingRegressionEncoder(
            ArrayList<Integer> data, int block_size, String dataset_name) throws IOException {
        block_size++;
        ArrayList<Byte> encoded_result = new ArrayList<Byte>();
        int length_all = data.size();
        byte[] length_all_bytes = int2Bytes(length_all);
        for (byte b : length_all_bytes) encoded_result.add(b);
        int block_num = length_all / block_size;

        byte[] block_size_byte = int2Bytes(block_size);
        for (byte b : block_size_byte) encoded_result.add(b);


//        for (int i = 0; i < 1; i++) {
        for (int i = 0; i < block_num; i++) {
//            System.out.println("i="+i);
            ArrayList<Integer> ts_block = new ArrayList<>();
//            ArrayList<Integer> ts_block_reorder = new ArrayList<>();
            for (int j = 0; j < block_size; j++) {
                ts_block.add(data.get(j + i * block_size));
//                ts_block_reorder.add(data.get(j + i * block_size));
            }
            ArrayList<Integer> result2 = new ArrayList<>();

            splitTimeStamp3(ts_block, result2);
//            splitTimeStamp3(ts_block_reorder, result2);

            // time-order
            ArrayList<Byte> cur_encoded_result = learnKDelta(ts_block, 0);
            encoded_result.addAll(cur_encoded_result);

        }

        int remaining_length = length_all - block_num * block_size;
        if (remaining_length <= 3) {
            for (int i = remaining_length; i > 0; i--) {
                byte[] timestamp_end_bytes = int2Bytes(data.get(data.size() - i));
                for (byte b : timestamp_end_bytes) encoded_result.add(b);
            }

        } else {
            ArrayList<Integer> ts_block = new ArrayList<>();

            for (int j = block_num * block_size; j < length_all; j++) {
                ts_block.add(data.get(j));
            }
            ArrayList<Integer> result2 = new ArrayList<>();
            splitTimeStamp3(ts_block, result2);
            int supple_length;
            if (remaining_length % 8 == 0) {
                supple_length = 1;
            } else if (remaining_length % 8 == 1) {
                supple_length = 0;
            } else {
                supple_length = 9 - remaining_length % 8;
            }


            ArrayList<Byte> cur_encoded_result = learnKDelta(ts_block, supple_length);
            encoded_result.addAll(cur_encoded_result);
        }


        return encoded_result;
    }


    public static ArrayList<ArrayList<Integer>> ReorderingRegressionDecoder(ArrayList<Byte> encoded) {
        ArrayList<ArrayList<Integer>> data = new ArrayList<>();
        int decode_pos = 0;
        int length_all = bytes2Integer(encoded, decode_pos, 4);
        decode_pos += 4;
        int block_size = bytes2Integer(encoded, decode_pos, 4);
        decode_pos += 4;

        int block_num = length_all / block_size;
        int remain_length = length_all - block_num * block_size;
        int zero_number;
        if (remain_length % 8 == 0) {
            zero_number = 1;
        } else if (remain_length % 8 == 1) {
            zero_number = 0;
        } else {
            zero_number = 9 - remain_length % 8;
        }

        for (int k = 0; k < block_num; k++) {
            ArrayList<Integer> time_list = new ArrayList<>();
            ArrayList<Integer> value_list = new ArrayList<>();

            ArrayList<ArrayList<Integer>> ts_block = new ArrayList<>();

            int time0 = bytes2Integer(encoded, decode_pos, 4);
            decode_pos += 4;
            int value0 = bytes2Integer(encoded, decode_pos, 4);
            decode_pos += 4;

            float theta0_r = bytes2float(encoded, decode_pos);
            decode_pos += 4;
            float theta1_r = bytes2float(encoded, decode_pos);
            decode_pos += 4;
            float theta0_v = bytes2float(encoded, decode_pos);
            decode_pos += 4;
            float theta1_v = bytes2float(encoded, decode_pos);
            decode_pos += 4;

            int max_bit_width_time = bytes2Integer(encoded, decode_pos, 4);
            decode_pos += 4;
            time_list = decodebitPacking(encoded, decode_pos, max_bit_width_time, 0, block_size);
            decode_pos += max_bit_width_time * (block_size - 1) / 8;

            int max_bit_width_value = bytes2Integer(encoded, decode_pos, 4);
            decode_pos += 4;
            value_list = decodebitPacking(encoded, decode_pos, max_bit_width_value, 0, block_size);
            decode_pos += max_bit_width_value * (block_size - 1) / 8;

            int td_common = bytes2Integer(encoded, decode_pos, 4);
            decode_pos += 4;

            int ti_pre = time0;
            int vi_pre = value0;
            for (int i = 0; i < block_size - 1; i++) {
                int ti = (int) ((double) theta1_r * ti_pre + (double) theta0_r + time_list.get(i));
                time_list.set(i, ti);
                ti_pre = ti;

                int vi = (int) ((double) theta1_v * vi_pre + (double) theta0_v + value_list.get(i));
                value_list.set(i, vi);
                vi_pre = vi;
            }

            ArrayList<Integer> ts_block_tmp0 = new ArrayList<>();
            ts_block_tmp0.add(time0);
            ts_block_tmp0.add(value0);
            ts_block.add(ts_block_tmp0);
            for (int i = 0; i < block_size - 1; i++) {
                int ti = (time_list.get(i) - time0) * td_common + time0;
                ArrayList<Integer> ts_block_tmp = new ArrayList<>();
                ts_block_tmp.add(ti);
                ts_block_tmp.add(value_list.get(i));
                ts_block.add(ts_block_tmp);
            }
//            quickSort(ts_block, 0, 0, block_size - 1);
            data.addAll(ts_block);
        }

        if (remain_length == 1) {
            int timestamp_end = bytes2Integer(encoded, decode_pos, 4);
            decode_pos += 4;
            int value_end = bytes2Integer(encoded, decode_pos, 4);
            decode_pos += 4;
            ArrayList<Integer> ts_block_end = new ArrayList<>();
            ts_block_end.add(timestamp_end);
            ts_block_end.add(value_end);
            data.add(ts_block_end);
        }
        if (remain_length != 0 && remain_length != 1) {
            ArrayList<Integer> time_list = new ArrayList<>();
            ArrayList<Integer> value_list = new ArrayList<>();

            ArrayList<ArrayList<Integer>> ts_block = new ArrayList<>();

            int time0 = bytes2Integer(encoded, decode_pos, 4);
            decode_pos += 4;
            int value0 = bytes2Integer(encoded, decode_pos, 4);
            decode_pos += 4;

            float theta0_r = bytes2float(encoded, decode_pos);
            decode_pos += 4;
            float theta1_r = bytes2float(encoded, decode_pos);
            decode_pos += 4;
            float theta0_v = bytes2float(encoded, decode_pos);
            decode_pos += 4;
            float theta1_v = bytes2float(encoded, decode_pos);
            decode_pos += 4;

            int max_bit_width_time = bytes2Integer(encoded, decode_pos, 4);
            decode_pos += 4;
            time_list =
                    decodebitPacking(encoded, decode_pos, max_bit_width_time, 0, remain_length + zero_number);
            decode_pos += max_bit_width_time * (remain_length + zero_number - 1) / 8;

            int max_bit_width_value = bytes2Integer(encoded, decode_pos, 4);
            decode_pos += 4;
            value_list =
                    decodebitPacking(
                            encoded, decode_pos, max_bit_width_value, 0, remain_length + zero_number);
            decode_pos += max_bit_width_value * (remain_length + zero_number - 1) / 8;

            int td_common = bytes2Integer(encoded, decode_pos, 4);
            decode_pos += 4;

            int ti_pre = time0;
            int vi_pre = value0;
            for (int i = 0; i < remain_length + zero_number - 1; i++) {
                int ti = (int) ((double) theta1_r * ti_pre + (double) theta0_r + time_list.get(i));
                time_list.set(i, ti);
                ti_pre = ti;

                int vi = (int) ((double) theta1_v * vi_pre + (double) theta0_v + value_list.get(i));
                value_list.set(i, vi);
                vi_pre = vi;
            }

            ArrayList<Integer> ts_block_tmp0 = new ArrayList<>();
            ts_block_tmp0.add(time0);
            ts_block_tmp0.add(value0);
            ts_block.add(ts_block_tmp0);
            for (int i = 0; i < remain_length + zero_number - 1; i++) {
                int ti = (time_list.get(i) - time0) * td_common + time0;
                ArrayList<Integer> ts_block_tmp = new ArrayList<>();
                ts_block_tmp.add(ti);
                ts_block_tmp.add(value_list.get(i));
                ts_block.add(ts_block_tmp);
            }

//            quickSort(ts_block, 0, 0, remain_length + zero_number - 1);

            for (int i = zero_number; i < remain_length + zero_number; i++) {
                data.add(ts_block.get(i));
            }
        }
        return data;
    }

    public static void main(@org.jetbrains.annotations.NotNull String[] args) throws IOException {
        String parent_dir = "/Users/xiaojinzhao/Desktop/encoding-outlier/"; ///Users/xiaojinzhao/Desktop
        String output_parent_dir = parent_dir + "vldb/compression_ratio/block_size";
        String input_parent_dir = parent_dir + "iotdb_test_small/";
        ArrayList<String> input_path_list = new ArrayList<>();
        ArrayList<String> output_path_list = new ArrayList<>();
        ArrayList<String> dataset_name = new ArrayList<>();
        ArrayList<Integer> dataset_block_size = new ArrayList<>();
        dataset_name.add("CS-Sensors");
        dataset_name.add("Metro-Traffic");
        dataset_name.add("USGS-Earthquakes");
        dataset_name.add("YZ-Electricity");
        dataset_name.add("GW-Magnetic");
        dataset_name.add("TY-Fuel");
        dataset_name.add("Cyber-Vehicle");
        dataset_name.add("Vehicle-Charge");
        dataset_name.add("Nifty-Stocks");
        dataset_name.add("TH-Climate");
        dataset_name.add("TY-Transport");
        dataset_name.add("EPM-Education");

        for (int i = 0; i < dataset_name.size(); i++) {
            input_path_list.add(input_parent_dir + dataset_name.get(i));
        }

        output_path_list.add(output_parent_dir + "/CS-Sensors_ratio.csv"); // 0
        dataset_block_size.add(1024);
        output_path_list.add(output_parent_dir + "/Metro-Traffic_ratio.csv");// 1
        dataset_block_size.add(512);
        output_path_list.add(output_parent_dir + "/USGS-Earthquakes_ratio.csv");// 2
        dataset_block_size.add(512);
        output_path_list.add(output_parent_dir + "/YZ-Electricity_ratio.csv"); // 3
        dataset_block_size.add(256);
        output_path_list.add(output_parent_dir + "/GW-Magnetic_ratio.csv"); //4
        dataset_block_size.add(128);
        output_path_list.add(output_parent_dir + "/TY-Fuel_ratio.csv");//5
        dataset_block_size.add(64);
        output_path_list.add(output_parent_dir + "/Cyber-Vehicle_ratio.csv"); //6
        dataset_block_size.add(128);
        output_path_list.add(output_parent_dir + "/Vehicle-Charge_ratio.csv");//7
        dataset_block_size.add(512);
        output_path_list.add(output_parent_dir + "/Nifty-Stocks_ratio.csv");//8
        dataset_block_size.add(256);
        output_path_list.add(output_parent_dir + "/TH-Climate_ratio.csv");//9
        dataset_block_size.add(512);
        output_path_list.add(output_parent_dir + "/TY-Transport_ratio.csv");//10
        dataset_block_size.add(512);
        output_path_list.add(output_parent_dir + "/EPM-Education_ratio.csv");//11
        dataset_block_size.add(512);
        //        String parent_dir = "C:\\Users\\Jinnsjao Shawl\\Documents\\GitHub\\encoding-outlier\\";
//        String output_parent_dir = parent_dir + "vldb\\compression_ratio\\outlier";
//        String input_parent_dir = parent_dir + "iotdb_test_small\\";
//        ArrayList<String> input_path_list = new ArrayList<>();
//        ArrayList<String> output_path_list = new ArrayList<>();
//        ArrayList<String> dataset_name = new ArrayList<>();
//        ArrayList<Integer> dataset_block_size = new ArrayList<>();
//        dataset_name.add("CS-Sensors");
//        dataset_name.add("Metro-Traffic");
//        dataset_name.add("USGS-Earthquakes");
//        dataset_name.add("YZ-Electricity");
//        dataset_name.add("GW-Magnetic");
//        dataset_name.add("TY-Fuel");
//        dataset_name.add("Cyber-Vehicle");
//        dataset_name.add("Vehicle-Charge");
//        dataset_name.add("Nifty-Stocks");
//        dataset_name.add("TH-Climate");
//        dataset_name.add("TY-Transport");
//        dataset_name.add("EPM-Education");
//
//        for (int i = 0; i < dataset_name.size(); i++) {
//            input_path_list.add(input_parent_dir + dataset_name.get(i));
//        }
//
//        output_path_list.add(output_parent_dir + "\\CS-Sensors_ratio.csv"); // 0
//        dataset_block_size.add(1024);
//        output_path_list.add(output_parent_dir + "\\Metro-Traffic_ratio.csv");// 1
//        dataset_block_size.add(512);
//        output_path_list.add(output_parent_dir + "\\USGS-Earthquakes_ratio.csv");// 2
//        dataset_block_size.add(512);
//        output_path_list.add(output_parent_dir + "\\YZ-Electricity_ratio.csv"); // 3
//        dataset_block_size.add(256);
//        output_path_list.add(output_parent_dir + "\\GW-Magnetic_ratio.csv"); //4
//        dataset_block_size.add(128);
//        output_path_list.add(output_parent_dir + "\\TY-Fuel_ratio.csv");//5
//        dataset_block_size.add(64);
//        output_path_list.add(output_parent_dir + "\\Cyber-Vehicle_ratio.csv"); //6
//        dataset_block_size.add(128);
//        output_path_list.add(output_parent_dir + "\\Vehicle-Charge_ratio.csv");//7
//        dataset_block_size.add(512);
//        output_path_list.add(output_parent_dir + "\\Nifty-Stocks_ratio.csv");//8
//        dataset_block_size.add(256);
//        output_path_list.add(output_parent_dir + "\\TH-Climate_ratio.csv");//9
//        dataset_block_size.add(512);
//        output_path_list.add(output_parent_dir + "\\TY-Transport_ratio.csv");//10
//        dataset_block_size.add(512);
//        output_path_list.add(output_parent_dir + "\\EPM-Education_ratio.csv");//11
//        dataset_block_size.add(512);


        ArrayList<Integer> columnIndexes = new ArrayList<>(); // set the column indexes of compressed
        for (int i = 0; i < 2; i++) {
            columnIndexes.add(i, i);
        }

//        for (int file_i = 1; file_i < 2; file_i++) {
        for (int file_i = 0; file_i < input_path_list.size(); file_i++) {

            String inputPath = input_path_list.get(file_i);
            System.out.println(inputPath);
            String Output = output_path_list.get(file_i);

            int repeatTime = 1; // set repeat time

            File file = new File(inputPath);
            File[] tempList = file.listFiles();

            CsvWriter writer = new CsvWriter(Output, ',', StandardCharsets.UTF_8);

            String[] head = {
                    "Input Direction",
                    "Encoding Algorithm",
                    "Encoding Time",
                    "Decoding Time",
                    "Points",
                    "Compressed Size",
                    "Block Size",
                    "Compression Ratio"
            };
            writer.writeRecord(head); // write header to output file

            assert tempList != null;

            for (File f : tempList) {
//                f = tempList[2];
                System.out.println(f);
                InputStream inputStream = Files.newInputStream(f.toPath());

                CsvReader loader = new CsvReader(inputStream, StandardCharsets.UTF_8);
                ArrayList<Integer> data1 = new ArrayList<>();
                ArrayList<Integer> data2 = new ArrayList<>();
                ArrayList<ArrayList<Integer>> data_decoded = new ArrayList<>();


//                for (int index : columnIndexes) {
                // add a column to "data"
//                    System.out.println(index);

                loader.readHeaders();
//                    data.clear();
                while (loader.readRecord()) {
//                        String value = loader.getValues()[index];
                    data1.add(Integer.valueOf(loader.getValues()[0]));
                    data2.add(Integer.valueOf(loader.getValues()[1]));
//                        data.add(Integer.valueOf(value));
                }
//                    System.out.println(data2);
                inputStream.close();
//                for (int block_size_i = 9; block_size_i < 10; block_size_i++) {
                for (int block_size_i = 13; block_size_i > 3; block_size_i--) {
//                for (int block_size_i = 4; block_size_i < 14; block_size_i++) {
                    int block_size = (int) Math.pow(2, block_size_i);
                    long encodeTime = 0;
                    long decodeTime = 0;
                    double ratio = 0;
                    double compressed_size = 0;
                    int repeatTime2 = 1;
                    for (int i = 0; i < repeatTime; i++) {
                        long s = System.nanoTime();
                        ArrayList<Byte> buffer1 = new ArrayList<>();
                        ArrayList<Byte> buffer2 = new ArrayList<>();
                        long buffer_bits = 0;
                        for (int repeat = 0; repeat < repeatTime2; repeat++) {
//                            buffer1 = ReorderingRegressionEncoder(data1, dataset_block_size.get(file_i), dataset_name.get(file_i));
                            buffer2 = ReorderingRegressionEncoder(data2, block_size, dataset_name.get(file_i));
                        }
//                        System.out.println(buffer2.size());
//                            buffer_bits = ReorderingRegressionEncoder(data, dataset_block_size.get(file_i), dataset_name.get(file_i));

                        long e = System.nanoTime();
                        encodeTime += ((e - s) / repeatTime2);
//                        compressed_size += buffer1.size();
                        compressed_size += buffer2.size();
                        double ratioTmp = (double) compressed_size / (double) (data1.size() * Integer.BYTES);
                        ratio += ratioTmp;
                        s = System.nanoTime();
                        //          for(int repeat=0;repeat<repeatTime2;repeat++)
                        //            data_decoded = ReorderingRegressionDecoder(buffer);
                        e = System.nanoTime();
                        decodeTime += ((e - s) / repeatTime2);
                    }

                    ratio /= repeatTime;
                    compressed_size /= repeatTime;
                    encodeTime /= repeatTime;
                    decodeTime /= repeatTime;

                    String[] record = {
                            f.toString(),
                            "Outlier",
                            String.valueOf(encodeTime),
                            String.valueOf(decodeTime),
                            String.valueOf(data1.size()),
                            String.valueOf(compressed_size),
                            String.valueOf(block_size_i),
                            String.valueOf(ratio)
                    };
                    writer.writeRecord(record);
                    System.out.println(ratio);
                }
//                break;

            }
            writer.close();

        }
    }

}
