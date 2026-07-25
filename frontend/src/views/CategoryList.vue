<template>
  <div class="max-w-6xl mx-auto">
    <h1 class="text-3xl font-bold mb-8">文章分类</h1>
    
    <div v-loading="loading" class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
      <div v-for="category in categories" :key="category.id" 
           class="bg-white dark:bg-gray-800 rounded-lg shadow-lg p-6 hover:shadow-xl transition-shadow duration-300 cursor-pointer"
           @click="goToCategory(category)">
        <h3 class="text-xl font-bold text-gray-900 dark:text-white mb-2">
          {{ category.name }}
        </h3>
<!--        <p class="text-gray-600 dark:text-gray-400 mb-4">-->
<!--          {{ category.description }}-->
<!--        </p>-->
        <div class="text-sm text-gray-500">
          {{ category.articleCount || 0 }} 篇文章
        </div>
      </div>
      
      <div v-if="categories.length === 0 && !loading" class="col-span-full text-center py-12 text-gray-500">
        暂无分类
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getCategoryList } from '@/api/category'
import { setSEO } from '@/utils/seo'

const router = useRouter()
const categories = ref([])
const loading = ref(false)

const loadCategories = async () => {
  loading.value = true
  try {
    const res = await getCategoryList()
    const categoryList = res.data || []

    // 分类接口已经返回 articleCount，避免依赖文章列表 VO 的 categoryId 字段重复统计。
    categories.value = categoryList.filter(category => (category.articleCount || 0) > 0)
  } catch (error) {
    // console.error('Failed to load categories:', error)
    categories.value = []
  } finally {
    loading.value = false
  }
}

const goToCategory = (category) => {
  router.push(`/articles?categoryId=${category.id}`)
}

onMounted(() => {
  setSEO({
    title: '分类列表',
    description: '浏览所有文章分类，按分类发现感兴趣的内容',
    type: 'website',
    siteName: 'Hazenix的后端札记',
  })
  loadCategories()
})
</script>
